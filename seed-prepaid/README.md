# seed-prepaid — the prepaid engine and its money roads

The seed's prepaid engine (`api/Prepaid`, the builder of a lane's money) and the money roads an operator's or a reseller's screen calls
on the switch that owns the root (`api/PrepaidAdmin`): purchase · credit (+ reparent) · accounts · balance. Plain Java: the application
mounts them on its own roads; the seed has no server and no config file (R0). Design: routesphere `docs/architecture/prepaid-engine-design.md`.

## A purchase through the ledger (L2b, L2c)

A purchase is ONE record of the root's switch ledger: the purchase row and an account per bucket of the offer with its opening
balance. A repeated reference answers the first purchase while the process runs (the switch ledger remembers it). The ledger must be a
ledger DB that caches the purchases; name its tables one by one:

```
ledger.entities:   packageaccount, packageaccountreserve, packagepurchase, catalog
ledger.cache-only: packageaccountreserve
```

`catalog` (C4, the owner 2026-10-28) names the catalog's two tables, `package` and `packageitem`: the catalog goes through the cached
ledger too, and a purchase's or an item's package is checked in it before the WAL (the database's foreign keys would refuse a row naming
a package the tier lacks, and a refused batch stops the write-behind). Every tier needs both tables and prime-context's changelog 006:
the ledger refuses to start otherwise, in words.

Never the word `package`: it names every table — `packageaccountcharge` too, the durable reference table of L2b, which the ad lane does NOT
adopt (the owner, 2026-10-27: an ad view is ONE call, its money held once at admission and returned in `finalize` on failure; there
is no retry, so there is nothing to repeat). The table and its switch `ledger.charge-references` stay off; they are a question for the
SMS port only.

The account ids are minted by the APPLICATION and written into the database as they are: the ledger DB names rtc-domain's
`PackageAccount` — its key is `@GeneratedValue(IDENTITY)` — as a key minted by the caller (mem-ledger's `keysMintedByTheCaller`, the
owner's option A of 2026-10-27), so the database row of an account carries the very id the cache holds. rtc-domain is not changed.

## What the application passes (R0)

The seed has no config file and no main class: it cannot run alone. A microservice (a Quarkus project) depends on the seed's modules
and hands every value in. For the ledger and the purchase that is:

| Value | Where the application takes it from | What the seed does with it |
|---|---|---|
| the mem-ledger WAL and queue path | its bootstrap, per root: `journal.dir` | `<dir>/ledger/wal`, `<dir>/ledger/queue` |
| the tables the ledger caches | its bootstrap: `ledger.entities`, `ledger.cache-only` | `LedgerDbSettings` (above) |
| the tier databases | its bootstrap: `switch-db`, the tenant tree | the root's database and every reseller tier of its tree |
| the ledger's own login | its bootstrap: `ledger.db` (`password-ref` = a name, never a value) | the ledger's DataSource |
| the bucket-next shards | one value of its own config | `BucketNext.builder().shards(...)` (below) |

ONE process per root moves its money (the live call's rule, D1): the switch that runs the sessions holds the ledger, and every other
writer — an officer's screen, the BSS — calls that switch's roads (`PrepaidAdmin`, mounted by the application). Nothing writes a
balance straight into the database: the ledger's next write would overwrite it from memory.

## The application's setting: every bucket-next shard

The ids come from **bucket-next** (the sharded id service) through seed-idgen's client. The application builds it from ONE value of
its own config and hands it in:

```java
IdSource ids = BucketNext.builder()
    .shards(config.get("idgen.shards"))          // e.g. "http://10.10.199.21:7001,http://10.10.198.21:7001"
    .timeout(Duration.ofSeconds(2))
    .build();
PrepaidAdmin admin = prepaid.admin().withIds(ids);
admin.purchase(root, tierDb, partnerId, offer, reference);
```

- **Every shard is named**, comma-separated — like Kafka's server list, but a bucket-next shard does not tell about the others: shard k
  of N hands out k, k+N, k+2N, … and the client must know them all.
- The client asks the shards in turn, skips one that is down, and **fails closed** when none answers: nothing is minted, the purchase
  writes nothing.
- The ids are minted **above** the highest id the ledger holds (`mintAbove`), so a table that existed before the service keeps its ids.
- The key's name is the application's (the seed reads no config); the seed names no service url anywhere.
- **The tenant on every mint** (ARCH-0100; the owner, 2026-10-30: one id service per installation, the tenant mandatory on every
  call). The service is tenant-first (`/api/v2/{tenant}/…`, one counter per (tenant, entity)) and seed-idgen's client has no default
  tenant: `PrepaidAdmin` passes **the root's tenant id** — the `root` word of `purchase(root, tierDb, …)`, tenant-master's tenant id,
  the same word as the root schema and the topic suffix (`btcl` on the bed) — on every mint of a purchase. The tier is never the
  tenant: a reseller's accounts are minted in its root's counters. The config value above names the shards only, never a tenant.

## The one-creator rule

The database's own counter is not the source of these ids any more (MySQL's AUTO_INCREMENT moves past an explicit id; PostgreSQL's
identity sequence does not move at all). So **in a lane on this engine, nothing else creates accounts — or purchases — in those tables
by the database's counter**: no portal INSERT, no auto-increment. Every writer that creates them takes its ids from the same
bucket-next cluster (S7: one creator per table).

## Tests

`admin/PurchaseThroughTheLedgerTest`, `admin/MoneyRoadsTest` (H2); `admin/lab/PurchaseMakesItsAccountsScenario` on H2, the lab MySQL
(127.0.0.1:3306, password by name from `LAB_MYSQL_PASSWORD`) and the lab PostgreSQL 16 (127.0.0.1:7544, the ad lane's tables
verbatim): ONE record, the database's ids = the cache's, a hold across a restart, a repeated reference; `admin/lab/PurchasesOverTwoLiveShardsTest`:
two REAL bucket-next shards on 127.0.0.1 (skipped when the binary is absent), 60 purchases at once over three tiers, a shard killed in
the middle, every id unique.
