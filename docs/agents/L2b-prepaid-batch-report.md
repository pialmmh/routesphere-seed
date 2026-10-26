# L2b — the prepaid engine on ONE record over all tiers, the LedgerDb, purchase — the report

**Round:** L2b (brief `docs/agents/L2b-prepaid-batch-brief.md`, commit 9f13bf3) · **branch** `prepaid-batch` off `routesphere-flow` 22a09cc ·
**to** the architect · **written** 2026-10-27 04:4x +0600 by the seed agent (Claude Opus 5.5). Nothing pushed. Nothing left running.

## 0 · In short

| # | Commit | What | Whole reactor (run · fail · error · skip) |
|---|---|---|---|
| — | 22a09cc | the L2a merge, OLD mem-ledger pin (the architect's number) | 790 / 0 / 0 / 9 |
| brief | 9f13bf3 | the brief; NEW mem-ledger 6523614 (clean copy) | 790 / 1 / 1 / 9 — the 2 reds are `TierAddedLiveTest`'s tests that pin the OLD mem-ledger (no `addDatabase`) |
| 1 | c05adc7 | ONE write path: `internal/UnitOfWork` → ONE `execCrud(WALEntryBatch)` | 794 / 0 / 0 / 9 |
| 2 | be89641 | all tiers in one record: rate and authorize every tier, then ONE unit; renewal, settlement, CHARGE_NOW one unit each | 802 / 0 / 0 / 9 |
| 2b | 5f03ec5 | durable references: a charge's reference row in the debit's unit, its refund in the credit's | 804 / 0 / 0 / 9 |
| 3 | cfb5e37 | LedgerDb: entities by name/prefix, every tier of the tree, the doorbell's words decide the reload | 812 / 0 / 0 / 9 |
| 4 | 92b36ae | purchase: the purchase cached (3rd money entity), the road as ONE unit, ids from the app's IdSource; S4 closed | 818 / 0 / 0 / 9 |
| 5 | 50c1f1d | the money roads as plain Java: purchase · credit (+ reparent) · accounts · balance, refusing in words | 820 / 0 / 0 / 9 |
| 6 | 3317945 | this report | — |
| 7 | edb3e7f | the gate fix (§14): a free candidate with tiers after a hanging ledger; a ledger without a batch gives back leaf first with the refusal's cause | 822 / 0 / 0 / 9; ad-sphere 239f57b on it 998 / 0 / 0 / 7 |

**The code head 50c1f1d on a git-archive clean copy: 820 / 0 / 0 / 9**, exit 0 (§9); statewalk 4.1.0 only; mem-ledger 1.0.0-SNAPSHOT = 6523614's jar.

**The 9 skipped** = the base's 3 (seed-context 1, seed-routing 2) + the 6 base-lane gap rows of the parity suite (P2 P3 P9 P10 P13 P14) — as at
22a09cc. No `@Disabled` gap is closed by this round: the six are hooks the answers-only lane lacks, not the order of operations.

**One thing blocks production (a mem-ledger need, §7.1):** a purchase through the ledger cannot write its ACCOUNTS yet — rtc-domain's
`PackageAccount.id` is `@GeneratedValue(IDENTITY)` and mem-ledger leaves such a key to the database on INSERT. The road refuses in words
until mem-ledger writes the key a post-image carries. Everything else of the purchase is built and proven on the cache and the WAL.

## 1 · The mem-ledger I built against (§0 of the brief)

- `git -C routesphere/mem-ledger archive 6523614` → `~/l2b-work/mem-ledger-6523614`; `mvn -o -B -DskipTests -Dmaven.repo.local=~/l2b-work/m2 clean install`
  under the lock, exit 0. The private repository was made by `private-m2.sh`; its mem-ledger directory was copied fresh (0 shared inodes) before
  the install.
- The jar: `~/l2b-work/m2/com/telcobright/mem-ledger/1.0.0-SNAPSHOT/mem-ledger-1.0.0-SNAPSHOT.jar`, sha256 `b96889370a2b999d…` = the clean
  copy's `target/` jar. `javap`: `MemLedger.execCrud(WALEntryBatch)` (default), `MemLedger.addDatabase(String)` (default),
  `MemLedger.reloadNewRows()`; `MemLedgerImpl.execCrud(WALEntryBatch)`, `MemLedgerImpl.addDatabase(String)`.
- `~/.m2/repository/com/telcobright/mem-ledger/` hashed before and after (53 files): unchanged. Nothing installed into `~/.m2`.
- The seed resolves `com.telcobright:mem-ledger:jar:1.0.0-SNAPSHOT` (dependency:tree, §9) from the private repository.

## 2 · Item 1 — one write path (c05adc7)

**Files.** `seed-switch-ledger`: `internal/UnitOfWork` (new), `internal/Books` (every move staged in a unit), `internal/MemLedgerPort`,
`api/Credit`, `internal/OrphanReaper`, `internal/NewRowsOfATier` (each a one-unit operation), `internal/AddTierLive` (a plain
`MemLedger.addDatabase(tier)`); tests `internal/UnitOfWorkTest` (4), `TierAddedLiveTest` (the 2 pin tests replaced), `LedgerLab.walRecords()`
/ `entriesOfTheLastRecord()` (the real queue read by the lab's own tailer, mem-ledger L1's way).

**The rules.** A unit collects `(tier, entity, op, id, post-image)`; `commit(what)` = ONE `execCrud(WALEntryBatch)`; a refusal → `LedgerFault`
in the ledger's words, nothing changed. A row staged earlier is read from the unit. Two moves of one row = ONE post-image (INSERT+UPDATE →
INSERT; UPDATE+UPDATE → UPDATE; INSERT+DELETE → nothing; UPDATE+DELETE → DELETE; DELETE+INSERT → UPDATE; others refused in words). Every
image staged is a new object: growing a reserve row no longer changes the cached row before the WAL has it (design §3, A3).

**The grep at the head (50c1f1d)** — every ledger write left in the seed's main code (`~/l2b-work/logs/6-grep-writes-head.txt`):

```
seed-switch-ledger/src/main/java/com/telcobright/seed/switchledger/internal/UnitOfWork.java:131:        EntityResponse answer = Books.guarded(what, () -> ledger.execCrud(record));
```

No `execCrud(CrudRequest)`, no `insert / update / delete / updateWithDelta` anywhere else.

**Reds.**
```
1-commit-per-entry:  aUnitOverThreeTiers… "[ONE record for the three tiers] expected: 1L but was: 6L"
                     aRefusedEntry…      "[no record] expected: 0L but was: 4L"  (4 entries landed before the refused 5th)
                     theSameRowTwice…    "expected: 1L but was: 2L"
1-read-the-cache:    theSameRowTwice…    NullPointerException in Books.growReserveRow (the row staged in the unit not seen)
```

## 3 · Item 2 — all tiers in one record (be89641)

**The base** (`seed-sessionflow`): `spi/LedgerUnit` (lock · balanceOf · reserve · charge · settle · release · refund · commit · close);
`LedgerPort.unit()` — default `internal/DirectUnit` for a ledger that cannot write a batch (orchestrix's roads, a lane's own port, the
testkit's): each move at once, a unit closed without its commit gives back its holds and charges, newest first (the old saga, kept ONLY there);
`PerRootLedger.unit()` (the unit of the root of its first tier; another root refused in words); `MoneyEngine.holdTheWalk(ctx)` (default
nothing), called by `AdmissionChain` once every tier passed its checks, before the route; `RoutesphereContext.moneyPlan` (transient).

**The switch ledger:** `internal/SwitchUnit` — the call switch's moves (moved from `MemLedgerPort`, the cited lines kept) staged in ONE
`UnitOfWork`, ONE record at the commit; `internal/Answers` (the idempotent references) written only after the record. `AccountLocks`: ONE
lock per (tier, account), made when asked and dropped when free, and `holdAll(keys)` in the global order **(tier, account id)** whatever
order the caller names. The old table was STRIPED (1024 stripes): on a striped table an order on the keys is no order on the locks
(mem-ledger L1's finding `i1-c2`) — with one lock per key, (tier, account id) IS the lock order. `OwnedMemLedgerPort.unit()` holds the
gate shared until it closes (`GatedUnit`).

**An L2a gap found and closed:** `OwnedMemLedgerPort` (the port a kit FROM THE BOOTSTRAP is handed) did not pass `charge`, `refund`,
`credit`, `reparent` through — on the bootstrap kit a CHARGE_NOW lane and the credit road were refused "OwnedMemLedgerPort takes no final
charge". Now delegated (`OwnedPortTakesEveryVerbTest`).

**The engine** (`seed-prepaid`): `admit` PLANS a tier (`internal/WalkPlan`: its accounts to lock and its hold) — the lane's answers and the
Prepaid's account walk alike; a dry run rates as before. `holdTheWalk` opens ONE `internal/MoneyUnit`, locks every account of every tier,
asks each tier's hold through the moved `TierReserve` (unchanged rules: the first account that pays, the lane's refusal cause) and commits only
when every tier pays. What a move records on the session (the reserve facts, the walk, the journal, the low-balance mark) is applied only
after the record. `release` (a refusal after the hold — the route, the last gate), `renewWindow` (every tier's grant; the narrowest decides;
then `onExhausted`) and `finish` (every tier's Final; the session's money lock taken BEFORE the accounts' — the renewal's order, no lock
inversion) are each ONE unit. A unit the ledger refuses: release → every tier OWED; renewal → nothing held, the session goes on (a fault
never cuts); settlement → every tier OWED with its reference (`internal/UnitFault`). `LeftoverMoney` (the start) keeps one move per call.

**Tests (new):** `SwitchUnitTest` (4, three tiers btcl → res_2 → res_3): the hold of three tiers = 1 record of 6 entries; a short 3rd tier →
tiers 1–2 untouched, no row, no record; the 3rd tier's lock held elsewhere → `LedgerFault "the lock of account 1 in res_3 was not free within
400 ms"`, nothing anywhere, the locks given back; two units over the same three accounts in OPPOSITE tier orders, two threads, 150 rounds
started together — all finish. `OneRecordPerOperationTest` (3, the call on its Prepaid, two tiers): the hold, a renewal, the settlement = one
record each (4 entries each); the root cannot pay → the leaf untouched, no row, no record; the SMS's charge of both tiers = one record, its
refund one more. `OwnedPortTakesEveryVerbTest` (1).

**Assertions changed — only saga witnesses, the money the same:**

| Test | Was | Now | Why |
|---|---|---|---|
| `CallParityAdmissionTest.P5` (and its two Prepaid subclasses) | `recorder.seen` has `release p5#L0` | the recorder saw nothing; WAL records unchanged | the brief: "the saga's compensation for an in-process refusal goes (nothing was written)"; design §10.3 P5 "nothing to return". The cause and the balances/rows assertions unchanged |
| `SmsParityTest.S5` | `charge s5#L0` then `refund s5#L0` | the recorder saw nothing; no record | the same rule, charged-now |
| samples `VoiceFlowTest` c-df (the root's digit filter) | `reserve c-df` 1, `release c-df` 1 | 0, 0 | the same rule; c-mb (a middle tier short, on the testkit's ledger = the DirectUnit saga) is still reserve 1 · refused 1 · release 1 |

`ParityScene.RecordingPort` gained `unit()` (a recording unit: each move noted with the port's own words, then staged in the real unit) — so
P6/P8's `reserve p6#L0#W2`, `locked=true` witnesses read the same.

**Reds.**
```
2-unsorted-locks (the caller's order):  twoUnitsOverTheSameThreeAccounts… ["B round 0: the lock of account 1 in btcl was not free within 400 ms",
                                         "A round 0: the lock of account 1 in res_2 was not free within 400 ms"]
2-no-unit (every move its own record):  OneRecordPerOperation 3/3 "expected: 1L but was: 2L"; theRootCannotPay… "expected: 2.5 but was: 1.500000"
                                         (the leaf held and never given back)
2-owned-port-no-charge:                 "OwnedMemLedgerPort takes no final charge (CHARGE_NOW): the switch ledger does"
```

## 4 · Item 2b — durable references (5f03ec5)

**Read first.** The live SMS debit (`PrepaidServiceWithCompensation.debit` 441–516) has NO idempotency: a repeated debit takes twice; its
refund is a recharge. rtc-domain's money tables: `packageaccountreserve` (the hold's durable reference already: its row) and `acc_reserve`
(`event_id`, `acc_id`, `amount`; no `CacheableEntity`). Neither fits a charge: a reserve row is the orphan reaper's input — a charge kept there
with its amount would be GIVEN BACK after the reaper's age; `acc_reserve` cannot be cached. So a NEW table, the seed's `api/ChargeReference`.

**The rule.** A charge's reference row is staged in the SAME unit as its debit (one record, 2 entries); before anything moves the row is read
— a charge made before (this process or one before a restart) answers its first result, repeated, and takes nothing. A refund reads its
charge's row: none → `LedgerFault "no charge is held under …: nothing to refund"`; already refunded → the first refund's answer, nothing
moves; else the credit and the row's `refunded` in the SAME unit. Registered only where the root's ledger says `ledger.charge-references = on`
(or a ledger DB names it): no deployment needs the table until it asks; without it the in-memory references of L2a stay.

**The DDL (proposed; applied nowhere).** One table per tier (a MySQL database / a PostgreSQL schema):

```sql
-- MySQL (each tier database)
CREATE TABLE packageaccountcharge (
  reference         VARCHAR(255)  NOT NULL,
  id_packageaccount BIGINT        NOT NULL,   -- for a purchase's reference row (item 4): the PURCHASE's id
  uom               VARCHAR(45)   NOT NULL,   -- 'PURCHASE' for a purchase's reference row
  amount            DECIMAL(20,6) NOT NULL,
  balanceBefore     DECIMAL(20,6) NOT NULL,
  balanceAfter      DECIMAL(20,6) NOT NULL,
  refunded          DECIMAL(20,6) NULL,
  time              VARCHAR(64)   NULL,
  PRIMARY KEY (reference)
);

-- PostgreSQL (each tier schema; the ledger's role: SELECT, INSERT, UPDATE on it — never DELETE, a reference lives on)
create table packageaccountcharge (
  reference varchar(255) not null, id_packageaccount bigint not null, uom varchar(45) not null,
  amount numeric(20,6) not null, balanceBefore numeric(20,6) not null, balanceAfter numeric(20,6) not null,
  refunded numeric(20,6), time varchar(64), primary key (reference)
);
```

The rows only grow (one per charge, one per purchase): a retention rule (e.g. older than 90 days) is the owner's decision; the reference
must outlive any repeat a client can send.

**Tests.** `DurableChargeTest` (2, a restart = a new MemLedger over the same H2 and WAL + a new switch ledger with an empty memory): the debit
and its reference = ONE record of 2 entries; restart; the same charge → repeated, balance 8 (charged once); a charge, restart, its refund →
10; restart, the refund again → the first refund's answer, still 10.

**Reds.**
```
2b-reference-in-a-second-unit: "expected: 1L but was: 2L"
2b-reference-not-read:          the repeat after the restart refused by mem-ledger itself — "Batch refused … entry 2 of 2
                                (btcl.PackageAccountCharge id=sms-1#L0): INSERT of a row the cache holds" (a second net; a LedgerFault, not the first answer)
2b-refund-not-read:             "expected: 10 but was: 12.000000" (given back twice)
```

## 5 · Item 3 — LedgerDb, a seed feature (cfb5e37)

**Files.** `seed-switch-ledger`: `dependencies/LedgerDbSettings` (values only: entities by name or table prefix, cache-only tables, WAL /
queue dirs, reload spacing — the tiers are never configured), `internal/LedgerEntities` (the known entities: PackageAccount / packageaccount,
PackageAccountReserve / packageaccountreserve, PackageAccountCharge / packageaccountcharge, PackagePurchase / packagepurchase; a word that IS
a name or table names that one, otherwise every table it prefixes — `package` = all four; a word naming nothing, a set without the account
and the reserve, a cache-only table not cached: refused in words), `api/LedgerDb` (`register`, `decide`), `api/Doorbell`, `TwoEntities.check(ledger,
configured)` (EXACTLY the configured entities), `OwnedMemLedgerPort.doorbell(…)`, `SwitchLedgerMaker` (`ledger.entities`, `ledger.cache-only`
from the app's bootstrap; not said → the two tables as before), `NewRowsOfATier` on `reloadNewRows()` (S6: no more the deprecated
`reloadNewEntities`; a partial reload said, the rest merged).

**The signals** (no new topic, no new consumer):

| Signal | The cache does |
|---|---|
| `tenant_created` | the existing road (`TiersFirst` → `tierAdded` → `addDatabase`, now a plain call) |
| doorbell `tier.table:INSERT`, a cached table, not cache-only | ONE reload of new rows, coalesced (`reloadNewRows()` whole) |
| doorbell `*:SNAPSHOT`, or `what` = start, stream-open, reload, migrate-all, nightly | the catch-up (the same reload) |
| doorbell naming a tier the ledger lacks | that tier loaded (a lost `tenant_created`) |
| UPDATE / DELETE of its own tables, a table it does not cache, a cache-only table's INSERT | nothing |
| a tree's reload alone (over a ledger DB) | nothing — S2 closed: a rule's or a campaign's ring no longer re-reads every cached table of every tier |

**How the words arrive:** seed-config-client `KafkaTopicsDoorbellSource` hands each record's (topic, value) before its topic rings →
seed-context `TenantContexts.onDoorbellWords(listener)` (by tenant, from the topic) → seed-tenant `TenantTrees` / `LiveTenantTrees` → the
base's `LiveTrees.onDoorbellWords` (default none) → `BootstrapRoad` → `PerRootLedger.doorbell(root, message)` (the JSON read; unreadable → one
WARN) → `LedgerPort.doorbell(what, changes)` (default nothing) → `OwnedMemLedgerPort`.

**The targeted reload is mem-ledger's follow-up (not written):** `MemLedgerImpl.reloadNewRowsOf(db, entity, …)` is PRIVATE on main 6523614;
made public, `decide` would reload THAT table of THAT tier only (design §9.3).

**Tests.** `LedgerDbTest` (6): `res_9.packageaccount:INSERT` → ONE reload, the new account served; 50 rings in under a second → 1 or 2 reloads;
`packageaccountreserve:UPDATE`, `packageaccount:UPDATE`, `:DELETE`, `partner:INSERT`, the cache-only table's INSERT and a tree's reload → none;
`start` / `*:SNAPSHOT` / `stream-open` → one reload each; a tier the ring names and one by `tenant_created` → loaded and served with no restart;
exactly the configured entities. `DoorbellWordsReachTheLedgerDbTest` (1, the kit from the bootstrap with `ledger.entities` / `cache-only`):
the words through the trees make a new account pay; a tree's reload and the reserve's UPDATE re-read nothing. seed-context `DoorbellWordsTest` (1).

**Reds.**
```
3-every-op-reloads:   "expected: 0L but was: 1L" (own tables' UPDATE; the kit test too)
3-no-coalescing:      the fifty → "Expecting actual: 50 … between 1 and 2"; the P1-J burst "expected: 1L but was: 6L"
3-catch-up-ignored:   "expected: 1L but was: 0L"
3-tree-reload-merges: "expected: 0L but was: 1L"
```

## 6 · Items 4 and 5 — purchase and the money roads (92b36ae, 50c1f1d)

**The entity.** rtc-domain's `PackagePurchase` is NO `CacheableEntity` and carries JPA relations (`@ManyToOne Package pkg`, `@OneToMany
packageAccounts`) a MemLedger cannot load. The ledger caches `api/LedgerPurchase`, its FLAT twin on the SAME table `packagepurchase` (column
for column, `id_package` as the package's id) — no schema change.

**The purchase road** (base: `LedgerPort.purchase(buyer, Offer, reference, Ids)` + `Offer`, `Bucket`, `Ids`, `Purchased`; delegated by
`PerRootLedger`, `OwnedMemLedgerPort`): `internal/Purchases` — ONE unit: the purchase row, an account per bucket with its opening balance, the
reference row `purchase:<reference>`; ids from the APPLICATION's source above what the ledger holds (`admin().withIds(idSource)` adapts
seed-idgen's `mintAbove(table, LONG, 1, highestHeld)`; R0: no service url in the seed); the same reference → the first purchase, nothing
written twice.

**S4 closed.** `LedgerPort.accountsOf(buyer)`: the partner's accounts at its tier as the LEDGER holds them, each a copy with its purchase's
expiry — from a partner → accounts index made from the cache on its first ask, kept by the purchases, dropped when new rows were merged (a
reload, a tier) or an account moved (reparent). `AccountWalk` asks it first (`AccountRule` / `AccountOrder` over a list); a ledger that caches no
purchases answers empty → the tree, as before. `PrepaidAdmin.accounts` reads the ledger first too.

**The roads (item 5)**, plain Java on `PrepaidAdmin`: `purchase`, `credit` (+ `reparent`), `accounts`, `balance` (new) — each refusing in words
(a tier not in the root's tree, an account the tier's ledger does not hold, a credit of 0 or less, a purchase without its id source / bucket /
dates / reference, a ledger with no purchase road, and the account ids made by the database — below).

**Tests.** `PurchasesTest` (1, the seed's part on the cache and the REAL WAL): ONE record of 4 entries (purchase, 2 accounts, reference), ids
above today's max (9 / 30), the cache holds them, the partner's accounts name them; the repeat = the first, no record. `PurchaseThroughTheLedgerTest`
(3): the purchase refused in words, nothing written; the partner's accounts and expiries from the ledger's purchases (another partner's not
listed); an expired purchase's account skipped by the order. `AccountsFromTheLedgerTest` (2, the call on its Prepaid over the ledger's
purchases): P2's scene pays from the cash while the LEDGER's purchase is valid; once the ledger says it expired (the tree still says valid)
the cash is skipped, the call refused `INSUFFICIENT_BALANCE`, nothing held. `MoneyRoadsTest` (2): credit → balance; reparent → the expiry
follows the purchase; every road's refusal in its words. `PrepaidAdminTest`: L2a's "purchase refused, round L2b's" → "a purchase without the
application's id source is refused in words". `LedgerDbTest`: `package` now names the purchases too.

**Reds.**
```
4-purchase-in-two-units:        "expected: 1L but was: 2L"
4-reference-not-read:           mem-ledger refuses the repeat — "entry 4 of 4 (btcl.PackageAccountCharge id=purchase:pay-9): INSERT of a row the cache holds"
4-expiry-not-carried:           3 tests (the expiries, the order, the cash that pays)
4-walk-reads-the-tree:          "expected: \"INSUFFICIENT_BALANCE\" but was: null" (the tree's stale expiry let the cash pay)
5-balance-of-nothing-is-zero:   "Expecting code to raise a throwable"
5-a-purchase-of-nothing-passes: the bucket refusal's words missing
```

## 7 · mem-ledger needs found (reported, not written — mem-ledger is merged)

1. **Write the key a post-image carries, even for a `@GeneratedValue` column** (BLOCKS the purchase's accounts). rtc-domain's
   `PackageAccount.id` is `@GeneratedValue(IDENTITY)`; `EntityMetadataExtractor` marks it auto-generated and `EntitySQLPersister` leaves it
   out of every INSERT (the WAL consumer's and the start's replay). An account made through the ledger with a minted id is written with
   ANOTHER id (MySQL's auto-increment) — the cache and the database disagree on the account's id. Seen on H2: `NULL not allowed for column
   "id_packageaccount"; INSERT INTO res_2.packageaccount (id_PackagePurchase, name, lastAmount, balanceBefore, balanceAfter, uom)` at the
   replay. Ways: (a) mem-ledger: an INSERT whose post-image carries a non-null key writes it (or a builder flag per entity "keys minted by
   the caller"); (b) rtc-domain: drop `@GeneratedValue` on `PackageAccount.id` — then every other writer (the billing portal) must take ids
   from seed-idgen too (S7: one creator per table). Until then `Purchases` refuses in words ("cannot make accounts yet: PackageAccount.id is
   @GeneratedValue …"); `LedgerPurchase` has no generated key, so the purchase row itself is fine.
2. **The targeted reload** — `reloadNewRowsOf(db, entity, …)` public (design §9.3).
3. **A registered-entities read** — `TwoEntities` still learns what the ledger holds from `getStats().getCacheStatistics()["cache_contents"]`.

## 8 · The proposed change-wire property for S3 (proposed only — NOT applied, the file NOT edited)

In `~/fullstack-work/proposed/change-wire/switch-btcl.properties` (round FULLSTACK's proposal; I did not touch it), the line
`changewire.cdc.table.exclude-exact=…` would become:

```properties
changewire.cdc.table.exclude-exact=campaign_task,campaign_counter,cdr_state,acc_chargeable,databasechangelog,databasechangeloglock,packageaccountreserve,packageaccountcharge
```

Both tables are written by the switch ledger alone: their write-behind flushes would come back as changes → a tree rebuild → a ring (S3).
`packageaccount` stays watched: an outside writer's INSERT (the portal's account, until purchases go through the ledger) is what the ledger
DB reloads on; the ledger's own UPDATEs come back as `packageaccount:UPDATE`, which the ledger DB now ignores (§5) — prime-context still
rebuilds its tree on them: excluding UPDATEs of that table needs an operation filter change-wire does not have (a change-wire proposal, the
owner's). Per the profile's own comment the publication `switch_config_pub` (prime-context's) may not carry these tables at all — then the
line is the "second line".

## 9 · The gate: the code head 50c1f1d on a git-archive clean copy

- `~/l2b-work/tools/gate.sh 50c1f1d after-50c1f1d`: `git archive 50c1f1d` → `~/l2b-work/clean/after-50c1f1d`; `mvn -o -B
  -Dmaven.repo.local=~/l2b-work/m2 test` under the lock, 04:42:40–04:46:07, **exit 0, 820 / 0 / 0 / 9** — every module as in the worktree's
  run (log `~/l2b-work/logs/after-50c1f1d.log`, per class `logs/classes-after-50c1f1d.txt`).
- Per module (before = 9f13bf3 on the new mem-ledger → after):

| Module | 9f13bf3 | 1 | 2 | 2b | 3 | 4 | 5 = head |
|---|---|---|---|---|---|---|---|
| seed-switch-ledger | 52 (1 F, 1 E) | 56 | 61 | 63 | 70 | 71 | 71 |
| seed-prepaid | 235 (6 skip) | 235 | 238 | 238 | 238 | 243 | 245 |
| seed-context | 22 (1 skip) | 22 | 22 | 22 | 23 | 23 | 23 |
| the other 11 modules | 481 (2 skip) | 481 | 481 | 481 | 481 | 481 | 481 |
| **total** | **790/1/1/9** | **794/0/0/9** | **802/0/0/9** | **804/0/0/9** | **812/0/0/9** | **818/0/0/9** | **820/0/0/9** |

- **The parity suite** at the head: P1–P21 × 3 lanes (the base lane 21 with its 6 gaps `@Disabled`, the subclass Prepaid lane 21, the builder
  Prepaid lane 21) + the SMS-shaped S1–S6 = **69 / 0 / 0 / 6** — as at 22a09cc (69/0/0/6), the same assertions but the saga witnesses of
  P5 and S5 (§3). With the round's new rows in the same package (`OneRecordPerOperationTest` 3, `AccountsFromTheLedgerTest` 2) and
  `PrepaidAdminTest` 5: 79 / 0 / 0 / 6.
- **statewalk 4.1.0 only** — `dependency:tree -Dincludes=com.telcobright:mem-ledger,com.telcobright:statewalk,com.telcobright:state-walk,
  com.telcobright:statewalk-v2` on the clean copy (`logs/after-tree.log`): `statewalk:jar:4.1.0` in seed-sessionflow, seed-bootstrap-config,
  seed-switch-ledger, seed-prepaid, seed-tenant (compile) and seed-sessionflow-samples (test); no state-walk v1, no statewalk-v2.
  `mem-ledger:jar:1.0.0-SNAPSHOT` in the same six (through rtc-domain, as before the round), resolved from `~/l2b-work/m2` = 6523614's jar
  (sha256 `b96889370a2b999d…`).

## 10 · What L3 (ad-sphere) needs

- The ONE dependency line of L2a (`seed-prepaid`) — and nothing else to compile: its imports and code are unchanged (`LedgerPort` gained only
  defaults; `MoneyEngine` gained a default; ad-sphere's own `LedgerPort` implementations get the `DirectUnit` saga by default).
- Its own `LedgerPort`s (if any are used for money) move with no batch: a refused candidate still gets the old give-back through the
  `DirectUnit`. To get ONE record over all tiers it must run on the switch ledger.
- For the ledger DB: the app's bootstrap words per root — `ledger.entities: package` (or the list), `ledger.cache-only: packageaccountreserve`,
  and the tables in every tier schema (packageaccountcharge's DDL §4; `packagepurchase` exists). `ledger.charge-references: on` for the
  two-table mode.
- The money roads: mount `prepaid.admin().withIds(<its seed-idgen IdSource>)` — `purchase` (refused until §7.1), `credit`, `reparent`,
  `accounts`, `balance` — on its REST (design §5.4's paths); the officer screens call them, never the database.
- An ad view (HOLD) and a CHARGE_NOW lane now write ONE record per business operation; the parity rows show the money is unchanged.

## 11 · D1 — one writer per account across microservices, as I met it

The switch ledger is STANDALONE: ONE process writes a root's accounts (its WAL, its cache, its per-key locks are that process's). Item 2's
locks and units make every operation of THAT process atomic over all tiers; they do nothing across processes. Two microservices on the same
root (ad-sphere and the WiFi switch, or the SMS service later), each with its own MemLedger over the same tier schemas, are two writers of the
same `packageaccount` rows: each caches its own balance, each writes full-row post-images behind — the last flush wins, money is lost, and a
hold written by one is unknown to the other's reaper. The purchase and credit roads make it sharper: they must run on THE process that owns
the root's money. **Decision for the owner:** which service owns each root's ledger (one "money owner" per root), and how the others reach it
(its roads over the network, or one shared money service). Until then: one money-moving service per root (the README's rule already).

## 12 · Open with the owner (not decided)

- **D10** (unchanged): the live SMS charges only the LAST tier of the chain; the seed's CHARGE_NOW charges every tier (now in ONE record).
- **§7.1:** mem-ledger writes a minted key, or rtc-domain drops `@GeneratedValue` on `PackageAccount.id` (+ one creator per table, S7).
- **§4:** the `packageaccountcharge` table and its retention.
- **§11:** one money owner per root (D1).

## 13 · What I could not do, and why

- **Prove a purchase end to end** (its accounts persisted, a repeat after a restart): blocked by §7.1 — the replay fails on the account's
  INSERT. The seed's part is proven on the cache and the WAL; production refuses in words.
- **The doorbell words on a root whose Kafka is not seed-context's:** the words ride seed-context's one doorbell consumer; a root on another
  Kafka is heard as its trees are (no change).
- **The targeted reload** (§7.2) — mem-ledger's.
- **Class size:** every new or changed class is under 300 lines (`SwitchUnit` 260, `TierReserve` 298, `Purchases` 180); `BootstrapRoad` was over the rule before this round
  (342 → 343) and `RoutesphereFlow` as at L2a — not this round's split.
- Nothing pushed (the rule). No sudo, no systemctl, nothing listening (H2 in process). Scratch under `~/l2b-work`: the mutants' originals under
  `reds/`, every log under `logs/`, the STATE lines in `STATE.md`.

## 14 · The architect's ad-sphere gate on 3317945 — diagnosis and fix (commit edb3e7f)

**The gate:** ad-sphere 239f57b (ea1512f + the `seed-prepaid` line) on 3317945 = 987/2/2/7, against 998/0/0/7 on L2a's 431bd6e. I re-ran
the baseline with a copy of the gate tool that keeps its reports (`~/l2b-work/tools/p2gate-keep.sh`, `~/l2b-work/p2gate/l2a-431bd6e/`):
998/0/0/7 again.

**The reds were SEED regressions, all three on a ledger WITHOUT a batch** (the testkit's `InMemoryLedger`, ad-sphere's own return road) —
where the brief keeps the old give-back (the `DirectUnit`). The money was right each time; the shape of the give-back, and one admission
rule, were not:

| ad-sphere red | What the seed did at 3317945 | Before L2b | Fix (edb3e7f) |
|---|---|---|---|
| `AdCallStoryTest.item2b`:454 — `expected "INSUFFICIENT_BALANCE" but was "the unit was not committed"` | the DirectUnit's release carried its own words | the release carried the refusal's cause | `LedgerUnit.abandon(why)`; `holdTheWalk` abandons with the tier's cause |
| `AdReturnRoadStoryTest.three_tiers…`:331 — `["9007 L1 returned", "9071 L0 returned"]` | the DirectUnit gave back newest first | in the order held, the leaf first (`compensateReserves`) | the DirectUnit gives back in the order taken |
| `AdCallStoryTest.item6d`:597, `item6g`:660 — no view, `NoSuchElement` | `holdTheWalk` refused the WHOLE walk when the paid budget was spent — the free house ad (a partner, a tier, nothing to pay) was refused after the hanging ledger spent the budget | a tier with nothing to hold never asked the ledger; only a tier that PAYS is "not asked" (`ADMISSION_TIMEOUT`) | no check up front: each tier's hold checks what is left, as `TierReserve` always did |

**Is the new path under the budget?** Yes. On a ledger without a batch every hold of `holdTheWalk` goes through the port's BOUNDED reserve
(`reserve(level, amount, reference, withinMs)`, `withinMs` = what the admission has left) — item6d's log: "the ledger did not answer within
1500 ms (scripted: it takes 8000 ms)". On the switch ledger (in process) the unit takes every account's lock once, each wait bounded by
the ledger's lock timeout (5 s) — as each reserve's lock was before L2b; neither was ever bounded by the admission budget. `balanceOf` is
not on the admission path.

**Seed-side tests, red first** (`~/l2b-work/reds/g-seed-red-before-the-fix.log`): `AdmissionBudgetTest.aFreeCandidateWithItsTiers_
isStillAdmitted_afterAHangingLedgerSpentTheBudget` (the testkit's ledger hangs 5 s on the paid candidate's leaf; the free candidate walks two
tiers) — red: "[the free candidate with its tiers: BILLING_SYSTEM_ERROR] Expecting value to be true but was false"; `GiveBackWithoutABatchTest`
(three tiers, the top short: releases res_44_7 then res_44, each noted INSUFFICIENT_BALANCE) — red: "[the leaf's first] expected "res_44_7"
but was "res_44"". Mutants on the fixed code: the budget checked before every tier → the free candidate refused; newest first → the order;
the cause dropped → the note. Seed suite 820/0/0/9 → 822/0/0/9.

**The 11 tests fewer (987 vs 998) are not lost:** ad-sphere runs more surefire executions after `default-test` (`jvm-in-utc` and the next):
`PcStartListensOnLoopbackOnlyTest` 2, `TreelessWallClockInUtcTest` 2, `TreeUploadsKeepTheirTypeTest` 5, `TreeWallClockInUtcTest` 2 = 11.
`default-test` failed, so Maven never reached them. `default-test` is 987 in both runs.

**What L3 must change in ad-sphere for these:** nothing — with edb3e7f they pass as they are (the gate below). When ad-sphere moves its
money onto the switch ledger (a batch), its tests that count the testkit's releases of a refused candidate stay true only on the testkit's
ledger: on the switch ledger a refused candidate writes nothing and releases nothing (P5's rule).

**The gate on edb3e7f:** `bash ~/arch-drafts/tools/p2gate-ml.sh 239f57b edb3e7f` (05:13–05:22): mem-ledger
6523614 and the seed installed into the empty head, **test exit 0, 998 / 0 / 0 / 7** — every class's counts identical to 431bd6e's run (205
classes, `~/l2b-work/logs/ad-classes-{431bd6e,edb3e7f}.txt`); AdCallStoryTest 25/0/0/0, AdReturnRoadStoryTest 7/0/0/0; statewalk 4.1.0,
mem-ledger 1.0.0-SNAPSHOT, every seed module 2.0.0-SNAPSHOT (seed-idgen now among them: seed-prepaid needs it). Log copy
`~/l2b-work/logs/p2gate-edb3e7f-test.log`.
