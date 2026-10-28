# C2 — the cached ledger, equipped for exposing · report

**Round:** C2 of the owner's ruling of 2026-10-28 (design `docs/architecture/prepaid-engine-design.md` §14). Branch `c2-expose` in
routesphere-seed, off C1's code 3acaa5f. Nothing pushed; nothing deployed; no box touched.

The owner: "the cached ledger apis shall be equipped for exposing, so that in future we can point telco-rest to the cached ledger
offered by the seed library, through specific application e.g. routesphere, ad-sphere." The seed has no server (R0): it offers the
pieces; the application that runs the sessions mounts them.

## What an application mounts, and what TelcoREST calls

```
TelcoREST ──RemoteLedger (seed-ledger-client)──HTTP──▶ the application's resource ──▶ CachedLedgerRoads ──▶ the root's CachedLedger
           one unit = one request                      (ad-sphere / routesphere)        guard · allowance     one unit = ONE WAL record
```

```java
// the application (ad-sphere, routesphere)
CachedLedgerRoads roads = CachedLedgerRoads.over(kit.ledger())
    .guard(LedgerGuard.ofTokenHash(salt, tokenSha256))          // its own secret, from its secret store
    .ids(ids)                                                   // bucket-next, every shard
    .writes("packagepurchase", List.of("idPackage", "idPartner", …))
    .writes("packageaccount", List.of("idpackagePurchase", "name", "uom", "lastAmount", "balanceBefore", "balanceAfter"))
    .build();
// POST <mount>/<root>/units → roads.unit(root, authorization, json) · GET <mount>/<root>/rows/<tier>/<table>/<id> · …?field=value

// TelcoREST
RemoteLedger ledger = RemoteLedgers.builder().mount("http://<app>/ledger/").root("btcl").token(secrets::ledgerToken).build();
RemoteLedger.Unit unit = ledger.unit("topup:" + paymentRef);
RemoteLedger.Key purchase = unit.insert("btcl", "packagepurchase", purchaseRow);     // the key is minted by the application
unit.update("btcl", "packageaccount", topUp, Map.of("idpackagePurchase", purchase))
    .add("btcl", "packageaccount", topUp, "balanceAfter", amount);
RemoteLedger.Outcome outcome = unit.commit();      // Committed | Refused; no answer → RemoteLedgerFault: send again, same reference
```

## Items

| # | where | what |
|---|---|---|
| 1 | seed-switch-ledger `api/CachedLedgerRoads` | the roads, framework-free: `unit`, `row`, `rows` → status + JSON. 200 written or a repeat · 409 refused, nothing written · 400 a caller's mistake · 403 not allowed · 404 no such root or row · 503 the ledger did not answer |
| 2 | `spi/LedgerGuard` | the application's token check; `ofTokenHash(salt, sha256)` — salted SHA-256, constant-time compare (the house rule for a check on every request) |
| 3 | `internal/RoadAllowance` | what callers may do per table: write these fields, read, delete — nothing by default; the sessions' rows never; a new row never carries its key (one creator per table) |
| 4 | `internal/WireUnit`, `WireJson` | a unit's moves as JSON; `{"$key": n}` = the n-th insert's minted key, so a purchase and its accounts are ONE request; numbers exact both ways |
| 5 | new module `seed-ledger-client` | `RemoteLedger` (rows as maps — the caller keeps its own entities), `Transport` SPI, `HttpTransport`, `RemoteLedgers` builder. JDK HttpClient + Jackson only |
| 6 | `internal/RowRules` + `LedgerEntities.Shape` | the finding below: a unit's rows checked against their tables BEFORE the WAL; every number written at its column's scale |
| 7 | the C1 API | ids normalised to the key type (30, 30.0, "30" = one row, one lock); a unit committed inside another unit's locks is refused (the locks are re-entrant: its write would be undone) |

## The finding behind item 6

mem-ledger's WAL consumer rolls back a batch the database refuses, reads it again, and **stops after 10 failures in a row**: the root's
money then lives in RAM only. A CRUD open to callers could carry such a row. The ledger now refuses, in words, what it can see the
database would refuse — an empty required column, a text too long, a number with too many digits (more decimals too: the database would
round, the cache not), an account made or moved onto a purchase its tier lacks — and what would break a session: a purchase deleted under
its accounts, an account deleted under a session's reserve. **Still the caller's to get right:** a purchase's package (the package table
is not cached). **For the owner:** a mem-ledger round where a refused batch is parked with an alarm and the consumer goes on.

## Tests added

| test | engine | what it shows |
|---|---|---|
| `TelcoRestContractTest` (12) | real MemLedger + WAL, H2 with TelcoREST's own columns | TelcoREST's writes through the client into the roads: a new purchase with its accounts (minted keys), a renewal with a growing slab, an upgrade's carry-over refused when a session moved the balance then landing on a fresh read, add-agents, welcome once, priorities, a top-up whose lost answer is sent again and credits ONCE, a postpaid limit set up and lowered — `creditLimit` (TelcoREST's column) left alone by the ledger; the door: no token, a field not allowed, a key in a new row, a row the database would refuse (then the write-behind still runs) |
| `CachedLedgerRoadsTest` (6) | same | every status; a lock not free in time → 503, sent again → written once; required, decimals, deletes under accounts or a reserve; an old account on a missing purchase still takes a move but nothing is moved onto one; a unit inside another unit's locks refused |
| `RemoteLedgerTest` (4) | the client alone | one request per unit, moves in order, `$key`, exact numbers, the token asked each time; every answer mapped |
| `HttpTransportTest` (2) | JDK HttpServer on 127.0.0.1, ephemeral port | the unit POSTed below the mount with `Authorization: Bearer …`; no answer or too late → a fault |

## C2b — every column through the cached ledger (the owner, 2026-10-28: "do as you recommend")

TelcoREST's three columns go through the ledger too, none by SQL beside it — the same one path as every other write:

| Repo · branch | What |
|---|---|
| mem-ledger `inherited-columns` 719a8d2 | an entity persists the columns of its persistent parents (`@Entity` / `@MappedSuperclass`), the farthest first — so the seed's row can extend rtc-domain's without touching the live voice's class; an entity with no persistent parent keeps exactly its columns (pinned); a column declared twice is refused. Gate 274/0/0/0 (lab MySQL + lab PostgreSQL), 5 mutants red |
| seed `c2-expose` cd1b789, 83eb67d | `LedgerAccount extends PackageAccount` (+ `creditLimit`, `isSelected`), registered in both modes; every copy keeps the row's class and EVERY field (a session's balance move never empties the limit); `LedgerPurchase.paymentRef`, unique per tier; the row check looks only at what a unit writes, against the narrower schema (`name` required: NOT NULL in RTC-Manager's MySQL template) |
| prime-context `ledger-columns` 0179a3f, 1b746a4, 6ab717f | changelog `006-ledger-columns.xml`, both engines (PostgreSQL `ADD COLUMN IF NOT EXISTS`; MySQL one changeset per column, only what a tier lacks — TelcoREST's tiers have them, RTC-Manager's template has `isSelected`); the baseline and copy tests know the file's new version |
| ad-sphere `c2b-columns` 1f2fa37, 36a990a | the switch schema file follows 006: addition A10, the file regenerated (the export unchanged but for A10); the PostgreSQL ITs' own tables carry the three columns (their first gate showed what a tier without them does: the consumer refused the batch — `column "creditlimit" does not exist` — and stopped) |
| seed `c2-expose` (the start check) | `internal/TierColumns`: before a root's ledger starts, and before a tier is loaded live, every tier holds every column the cached rows write (mem-ledger's own extractor, so the same list it writes); otherwise refused in words naming the tier, the table, the column and prime-context's migrate-all. mem-ledger's loader passes over a missing column in silence (a debug line), so without this check the cache loads and only the write-behind breaks |

**The two paths a row could still poison** (foreign keys to tables the ledger does not cache): a purchase's `id_package` → `package`, and
on MySQL its `id_Partner` → `partner`. The caller must name a package and a partner that exist.

## Mutants

Every mutant: one rule broken in the source, the named tests run, RED by a failing test (never a compile error), the file restored
with a fresh mtime and checked by its hash.

| set | mutants | red |
|---|---|---|
| C2 (seed) | `c2-guard-open`, `c2-bearer-kept`, `c2-fields-unchecked`, `c2-key-in-a-new-row`, `c2-required-unchecked`, `c2-length-unchecked`, `c2-digits-unchecked`, `c2-purchase-unchecked`, `c2-purchase-always-asked`, `c2-purchase-deleted-under-accounts`, `c2-account-deleted-under-a-reserve`, `c2-nested-unit-allowed`, `c2-not-scaled`, `c2-server-json-inexact`, `c2-key-range-unchecked`, `c2-client-key-shifted`, `c2-client-no-token`, `c2-client-503-not-a-fault` | 18 / 18 |
| C2b (seed) | `copy-drops-fields`, `plain-class-copy`, `insert-keeps-plain-class`, `open-account-plain`, `unique-unchecked`, `oftype-exact-only`, `key-own-fields-only`, `check-every-field`, `name-not-required` | 9 / 9 |
| the start check (seed) | `cols-start-unchecked`, `cols-live-unchecked`, `cols-missing-table-silent`, `cols-case-sensitive` | 4 / 4 |
| inherited columns (mem-ledger) | `parents-ignored`, `any-base-class`, `mapped-superclass-ignored`, `twice-allowed`, `own-fields-first` | 5 / 5 |

## Gates

Every gate on a clean `git archive` of the commit (never the worktree), offline, the private Maven repo, under the one-maven lock.

| repo · commit | what | result |
|---|---|---|
| mem-ledger `inherited-columns` 719a8d2 | the suite, lab MySQL + lab PostgreSQL | 274/0/0/0 |
| seed `c2-expose` 6de9bf9 | the suite (C1 + C2 + C2b + the start check), the PostgreSQL lab in | 894/0/0/9 |
| ad-sphere `c2b-columns` 36a990a on seed 6de9bf9 | the suite + the switch-ledger PostgreSQL ITs | 1005/0/0/7 + AdSwitchLedgerPostgresIT 3/0, AdSwitchMoneyPostgresIT 2/0, AdTierCreatedPostgresIT 4/0/1 |
| prime-context `ledger-columns` 6ab717f | the default suite (no lab: the lab tests skip) · `-Ppostgres` on the lab PostgreSQL · the 006 and 005 MySQL lab tests by name | 662/0/0/85 · 666/1/0/18 — the one red `LedgerGrantsLabTest` was the lab's: its `prime_context` role a SUPERUSER (a superuser passes every grant check); with the role NOSUPERUSER CREATEROLE it is 7/0 · 2/0/0/0 |

The first ad-sphere gate (1f2fa37) was red in the ITs: their own tables lacked 006's columns, the consumer refused the batch
(`column "creditlimit" of relation "packageaccount" does not exist`) and stopped — what the start check now refuses in words. Fixed in
36a990a. Merged LOCAL: mem-ledger main f739824 and ad-sphere p2-adflow 9e4d620 (each tree = its gated commit's); the seed's and
prime-context's merges follow the C4 round's gates (one push).

## Open with the owner

| # | question | my default |
|---|---|---|
| C2-a | ~~TelcoREST's three columns outside the cached rows~~ — **closed by C2b** (the owner: "do as you recommend"): every column goes through the ledger | — |
| C2-b | durable references: without `packageaccountcharge` a repeat is remembered in memory only — a restart forgets it, so a gateway retry after a restart could credit twice | adopt `packageaccountcharge` wherever the roads are mounted |
| C2-c | a mem-ledger round: a batch the database refuses is parked with an alarm, the consumer goes on (today it stops after 10 tries) | yes, its own round |
| C2-d | the deploy order: a tier gets 006 (prime-context's migrate-all) BEFORE the new seed runs on it — the seed now refuses to start otherwise, in words | as said |

**Found for C4 (not fixed here).** ad-sphere's officer purchase on the switch's ledger (`SwitchMoney.createPurchase`, L3 B4) writes the
purchase with package id `0`. A tier's `packagepurchase.id_package` references `package`, and no tier holds a package 0: the first such
purchase would be refused by the database and stop the write-behind. The ITs' tables carry no foreign key, so no test saw it; nothing is
deployed with it. C4 gives a purchase a real package (the catalog question is the owner's) and proves the refusal first.
