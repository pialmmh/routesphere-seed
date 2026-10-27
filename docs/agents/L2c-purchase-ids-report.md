# L2c — the purchase makes its accounts under bucket-next's ids (seed) · report

**Round:** L2c of the prepaid engine (brief `docs/agents/L1b-L2c-minted-keys-brief.md`, committed 5922df1) · branch `purchase-ids` off
`routesphere-flow` 042feef · built against mem-ledger **8e8990a** (L1b, `minted-keys`).

**How the round ran.** An agent wrote stage 2's code and tests (uncommitted, untested) until the owner stopped it at 15:38. The architect
finished it: ran it, adapted it to the owner's rulings of the afternoon (below), ran the mutants, wrote this report.

## The owner's rulings that shape this round (2026-10-27)

| ruling | effect here |
|---|---|
| Account ids: **option A** — mem-ledger writes the id the row carries (L1b); rtc-domain unchanged | the ledger DB names `PackageAccount` as a key minted by the caller |
| Ids from **bucket-next** (sharded); the seed connects to every shard, "like the Kafka server list" | seed-idgen's `BucketNext.builder().shards(<every shard>)` — already the client; the purchase mints through it |
| **An ad view is ONE call**: held once at admission, returned in `finalize` on failure; no retry; "first failed view call = 1 failed call, simple" | `packageaccountcharge` (L2b's durable reference table) is NOT adopted: the labs no longer create it, the ledger DB names its three tables one by one (never the prefix `package`, which would name it), a repeated purchase reference is answered from memory while the process runs |
| **D1** as the live call: the switch process that runs the sessions owns the money; every other writer goes through its REST | README: "What the application passes (R0)" and the one-process rule |
| **R0**: the seed has no config file and is not runnable; the microservice passes every value, the mem-ledger path included | README table: `journal.dir` → `<dir>/ledger/wal|queue`, `ledger.entities`, `ledger.cache-only`, `switch-db`, `ledger.db`, the shard list |

## Items

| # | what | files |
|---|---|---|
| 4 | **The purchase makes its accounts.** `LedgerEntities.keysTheDatabaseWouldGenerate` finds the registered entities whose key is `@Id @GeneratedValue` (of the four known, only rtc-domain's `PackageAccount`; `PackageAccountReserve` is keyed by the call's uuid, the seed's `LedgerPurchase` and `ChargeReference` carry keys the seed always wrote). `LedgerDb.register` names them with mem-ledger's `keysMintedByTheCaller`. `Purchases` no longer refuses ("cannot make accounts yet" is gone). | `seed-switch-ledger` `api/LedgerDb`, `internal/LedgerEntities`, `internal/Purchases` |
| 5 | **The application's setting, in words.** `seed-prepaid/README.md` (new) and `PrepaidAdmin`'s javadoc: the shard list as one value of the APPLICATION's config (every shard named), fail closed, `mintAbove`; what the application passes (R0); the one-creator rule; the three tables named one by one. | `seed-prepaid/README.md`, `api/PrepaidAdmin` |
| 6 | This report. | `docs/agents/L2c-purchase-ids-report.md` |

## Tests

| test | engine | what it shows |
|---|---|---|
| `PurchaseMakesItsAccountsScenario` ×3 (`H2PurchaseMakesItsAccountsTest`, `MysqlPurchaseMakesItsAccountsLabTest`, `PostgresPurchaseMakesItsAccountsLabTest`) | H2 in MySQL mode · the lab MySQL 127.0.0.1:3306 · the lab PostgreSQL 16 127.0.0.1:7544 (the ad lane's tables verbatim from prime-context 83912d1, the ledger as a role with the purchase road's rights) | 1 a purchase = ONE record; the purchase and its two accounts above every id held (500); the database = the cache, under the same ids · 2 the same reference again → the first purchase, no record, no row twice · 3 a hold on the new account, a restart → the same accounts by the same ids; the hold found and settled after the restart |
| `PurchasesOverTwoLiveShardsTest` | two REAL bucket-next shards on 127.0.0.1 (free ports; skipped without the binary) + H2 | 60 purchases from six threads over three tiers, shard 1 killed after 30: every purchase made, 60 purchase ids and 120 account ids unique, all above the ids held, only even ids after the kill; then the database = the cache in every tier |
| `PurchaseThroughTheLedgerTest` (changed) | H2 | the production account: ONE record, ids above 21, the database rows under the minted ids (was: "refused in words") |
| `PurchasesTest` (changed) | H2 | the write-behind lands under the same ids |
| `KeysTheSeedMintsTest` (new) | — | of the four known entities, only `PackageAccount` is named |
| `MoneyRoadsTest` (changed) | H2 | the refusal list without "cannot make accounts yet" |

## Mutants

| mutant | what it breaks | red |
|---|---|---|
| `m4-account-not-named` | `LedgerDb` names no key as minted by the caller | `PurchasesTest`: "timed out waiting for: the minutes account in the database under its minted id" (the consumer leaves the key out; the lab table has no counter) |
| `m4-mint-not-above` | `PrepaidAdmin` asks `mint` instead of `mintAbove` | `PurchaseThroughTheLedgerTest` and the H2 scenario ×3: "Batch refused, nothing changed: entry 1 of 4 (res_2.PackagePurchase id=1): INSERT of a row the cache holds" |
| `m4-generated-key-unseen` | `LedgerEntities` looks for the wrong annotation | `KeysTheSeedMintsTest`; `PurchasesTest` (the row never appears under its id) |

All three red; every original restored with a fresh mtime and checked by hash (`~/l1b-work/reds/m4-*`).

## Suites

| tree | mem-ledger | result |
|---|---|---|
| clean copy of **042feef** (before; the L2b merge gate) | 6523614 | **822/0/0/9** |
| worktree (stage 2 finished) | 8e8990a | 833/0/0/9 (+11: `KeysTheSeedMintsTest` 1, the scenario on H2 3, MySQL 3, PostgreSQL 3, the live shards 1) |
| clean copy of **ce1ecb5** (after: the code-final commit; the architect's gate, 19:42–19:45) | 8e8990a | **833/0/0/9** |

**ad-sphere on this seed** (239f57b = p2-adflow ea1512f + the one `seed-prepaid` pom line, a gate copy only; every test run with
`-Dmaven.test.failure.ignore=true`, so no surefire execution is skipped after a red):

| pair | ad-sphere |
|---|---|
| OLD: seed 042feef + mem-ledger 6523614 | 998/1/0/7 |
| NEW: seed ce1ecb5 + mem-ledger 8e8990a | 998/1/0/7 |

The same ONE red on both: `LedgerTest.in_memory_charge_is_final_and_a_credit_is_the_only_way_back`. It is date-bound, not this round's:
the test fixes `LATER = 2026-09-27T06:00Z + 30 days = 2026-10-27T06:00Z`, while `InMemoryLedger` reads `Clock.systemUTC()`; since 12:00 +06
today the 30-day package counts as expired and `accountsOf` returns `[1]` instead of `[2, 1]`. The same test is on ad-sphere `main`,
`p2-adflow` and 9c881fc: every ad-sphere build from now shows it. The fix is ad-sphere's (one line: a fixed clock for the test's
ledger) — named for L3, not done here. A plain gate run (no failure.ignore) shows 987/1/0/7: the 11 tests of the later surefire
executions are skipped after the first execution's red.

The 9 skipped are L2b's (the base's 3 + the six parity gap rows). Resolved in the clean copy: `com.telcobright:statewalk:jar:4.1.0` ×6, `com.telcobright:mem-ledger:jar:1.0.0-SNAPSHOT` ×6 — the private repository's, installed from a `git archive` of 8e8990a (`javap`: `MemLedgerBuilder.keysMintedByTheCaller(Class...)`); `~/.m2/repository`'s mem-ledger jars hashed before and after: unchanged.

## What L3 (ad-sphere) needs

- **mem-ledger 8e8990a (or its merge) and this seed** — both local only until the owner says push.
- **The bootstrap block per root** (prime-context): `journal.dir` (the WAL/queue live under it), `switch-db`, `ledger.entities:
  packageaccount, packageaccountreserve, packagepurchase`, `ledger.cache-only: packageaccountreserve`, `ledger.db` with a `password-ref`.
- **One config value for the bucket-next shards** in ad-sphere's own config (every shard's URL). The hosts are the owner's to name; a dev
  profile can point at the local dev pair on 127.0.0.1:7001/7002.
- **PostgreSQL rights**: the ledger role needs INSERT on `packageaccount` and `packagepurchase` (ARCH-0077-F gives SELECT and UPDATE on
  the account table only) — a line in the ad lane's provisioning.
- **The one-creator rule**: the officer screens and the BSS call ad-sphere's purchase / credit roads; nothing INSERTs accounts or purchases
  into the ad lane's tables by the database's counter.
- `Admission.HOLD` for the view: one hold per tier at admission, `finalize` = `Final.charge(...)` on success, `Final.releaseAll()` on
  failure.

## Left as it is

`packageaccountcharge` and `ledger.charge-references` stay in the seed, off, unused by the ad lane — a question for the SMS port (with
D10). The targeted per-(tier, entity) reload is still a mem-ledger follow-up (L2b §7.2).
