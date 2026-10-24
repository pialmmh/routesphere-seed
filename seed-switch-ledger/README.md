# seed-switch-ledger — the switch ledger on the base

ARCH-0077-A (the owner, 2026-10-10): the WiFi session follows the CALL pattern strictly — its ledger is MemLedger over the package
accounts of the DynamicContext (the call switch's shape), reserved per tier per window and settled exactly at the end, never an HTTP
charge per window. A switch that follows the call (wifi-sphere) depends on THIS module, never on routesphere-core. The base
(seed-sessionflow) never depends on this module: the ad must not carry Chronicle. The call's own code (routesphere-core
`BalanceBillingService`, `PrepaidServiceWithCompensation`, `ReserveBalanceStep`, callflow-v3-voice `VoiceLedger`) does not change — it is
the model this module copies, with citation (the lines are named in every javadoc).

## The structure

```
api/            AccountOrder    the call switch's account order over the tree: fundable · buckets · cashOnly (pure functions)
                Credit          the ONE credit primitive: credit(dbName, accountId, delta, reference) · reparent(dbName, accountId, purchaseId)
                WindowRenewal   the call's C14 remainder: the whole window, else what is left in seconds (the WiFi's WINDING_DOWN)
                LiveBalance     a read of a tier's live balance (the remainder's peek)
                SwitchLedger    the doors over ONE MemLedger: port · balances · reaper · credit
spi/            (none — the port implemented is the base's spi/LedgerPort)
dependencies/   SwitchLedgers   over(memLedger, settings, clock) · memLedger(…) → LedgerPort · reaper(…) · credit(…)
                SwitchLedgerSettings(reaperMaxAgeMinutes, reaperBatchLimit, lockTimeoutMs) · standard() = (60, 500, 5000)
internal/       MemLedgerPort   the base's LedgerPort over com.telcobright.memledger.api.MemLedger (reserve · settle · release · balanceOf)
                Books           the raw moves, exactly PrepaidServiceWithCompensation's writes (lines cited), nothing else
                AccountLocks    the per-account lock, striped and bounded in time — ONE table per MemLedger, every door shares it
                OrphanReaper    reap(dbName): the reserve rows a dead session left behind, older than the max age, a batch per pass
                TwoEntities     the check at the build: exactly PackageAccount and PackageAccountReserve, nothing else
                ClosedTiers     what a closed tier answered, so a settle or a release asked twice moves nothing
```

## The money, in the base's three verbs

```
reserve(level, amount, reference)     under the account's lock: the LIVE row is read; balance < amount → EMPTY (the refusal — the reserve
                                      IS the affordability test); else ONE packageaccountreserve row keyed by the TIER's reference
                                      (<sid>#L<n>) is opened — a window (…#W<n>) GROWS it, reserveUnit += amount — and the account is
                                      debited (balanceBefore = old, balanceAfter = old − amount, lastAmount = −amount), both full-row
                                      writes through execCrud. Answer: Reservation(accountId, uom, amount, before, after, repeated=false).
                                      A repeat of a reference answers its first result, repeated=true, and debits nothing.
settle(level, charged)                toReturn = reservedOf(level) − charged, in EITHER direction (negative = the extra debit of an
                                      overrun); the row DELETED; TierSettlement.of(level, charged, balanceAfter). Never owed.
release(level, why)                   the whole row back to its account; the row deleted.
balanceOf(level)                      a read of the live row (LiveBalance), no write, no lock.
```

The account is the tier's own: `level.getPackageAccountId()` — the tree's `PackageAccount` the rating step chose (`TierRate.account`), or the
account a rotation named (O4); none → `LedgerRefusal("NO_ACCOUNT")`. A MemLedger that does not answer is a `LedgerFault` — `BILLING_SYSTEM_ERROR`
upstream, never a balance cause. The port answers in the process: `slowestAnswerMs() = 0`.

## The tiers are SCHEMAS: register every one

`MemLedgerPort` reads and writes an account by `(level.getDbName(), accountId)` — the TIER's own schema, as the call does
(`ReserveBalanceStep` reads `tenant.getContext()` of the level's tier; `partnerIdWisePackageAccounts` is loaded per schema). On PostgreSQL
the tiers are SCHEMAS of one database (routesphere: `btcl`, `res_2`, `res_2_1` …) and mem-ledger's `dbName` is only the qualifier of
`dbName.table`. So the host registers EVERY tier schema of the tree in `MemLedgerBuilder.databases(...)` — explicit names from the tree,
preferred over `autoDiscoverDatabases`. A schema the ledger does not serve is a `LedgerFault` at the first reserve, in words.

```java
MemLedger memLedger = MemLedger.builder()
    .dataSource(dataSource)
    .databases("btcl", "res_2", "res_2_1")                       // every tier schema of the served tree
    .registerEntity("PackageAccount", PackageAccount.class)      // the two entities — and no other
    .registerEntity("PackageAccountReserve", PackageAccountReserve.class)
    .walPath(…).queuePath(…).build();

SwitchLedger ledger = SwitchLedgers.over(memLedger, SwitchLedgerSettings.standard(), clock);
SessionFlowKit kit = SessionFlowKit.builder().ledger(ledger.port()) … .build();
schedule(() -> tree.schemas().forEach(ledger.reaper()::reap), everySlotReconcile);   // the host's cadence
```

## On a kit made from the bootstrap (P1-E items 6 and 13)

`dependencies.SwitchLedgerMaker` is the `switch` kind of `RoutesphereKit.fromBootstrap` (found on the classpath): per served root a
MemLedger over its `switch-db` (url, user, password by reference) with every tier schema of the root's tree, its WAL and queue in the root's
own directory (`<journal.dir>/ledger`), STANDALONE. **The password from the PROCESS environment only** (P1-K, the owner's ruling of
2026-10-24, "Environment only"): `password-ref: env:<NAME>` reads the variable the box's environment file sets (the secreteer), never the
bootstrap envelope's `secrets` (before P1-K it fell back to them); a variable not there refuses the root's ledger in words naming it and the
login, never a value, and nothing is connected (`PasswordFromTheEnvironmentOnlyTest`; `SwitchLedgerMakerTest`'s pin moved with it). The
port it answers (`internal.OwnedMemLedgerPort`) OWNS that MemLedger:

- **closed with the engine** — the bootstrap road closes the ledgers it made; a closed engine leaves no MemLedger thread
  (`SwitchLedgerOnTheBootstrapKitTest`);
- **a reseller tier created live** (R10: `tenant_created`, kind reseller — prime-context sends it only after the tier's tables are committed)
  reaches THAT root's port only (`LedgerPort.tierAdded(tierDb)`, a default that does nothing elsewhere), BEFORE the root's tree is reloaded —
  so no session sees the tier before its ledger can serve it. The port loads ONE tier schema, ONCE, with no money move of the root running
  meanwhile: every verb holds the port's gate shared, the load holds it alone (never under a session's reserve).

**The load is `MemLedger.addDatabase(schema)`** (mem-ledger fba1346, 2026-10-11, ARCH-0077-B2 item 3): the tier's registered entities loaded,
nothing else opened or reloaded, a tier already registered a no-op. The seed's pin — mem-ledger 1.0.0-SNAPSHOT as built on 2026-10-07 in the
build box's repository — predates it, so `internal.AddTierLive` finds it on the MemLedger at run time; a MemLedger without it is said in one
ERROR in words, the tier's money is refused (`LedgerFault`: the ledger has no such schema) until a later word or the next start loads it.
Not `reloadDatabases()`: in the pinned mem-ledger it works only on a MemLedger built with `autoDiscoverDatabases(prefix)`, whose discovery
is MySQL's `SHOW DATABASES LIKE '<prefix>%'` — a PostgreSQL switch database keeps its tiers as SCHEMAS, where it fails — and it loads every
new database under the prefix, not the one tier the event names. When the pin moves to a mem-ledger build that has `addDatabase`, the
reflection becomes a plain call.

**Then the rows beside the tier** (P1-G, P2 gap 8): a reseller's provisioning also makes the tier's OWN account at its PARENT (a
`packageaccount` row in the parent's schema); `addDatabase(tier)` does not see it, so the parent answered ACCOUNT_NOT_FOUND. After the
tier's schema, the port calls mem-ledger's `reloadNewEntities()` (on the pinned jar's API — a plain call), still holding its gate alone: every
row the cache lacks is merged, add-only, in every schema of the root (`internal.NewRowsOfATier`, `ParentRowsOfANewTierTest`). A RESERVE row
it would bring back is a ghost — a settled reserve whose row the write-behind has not deleted yet (the port is the only writer of its root's
reserves) — and is taken out again at once (left in, the orphan reaper would give its money back a second time). Its cost, once per tier
created live: the pinned mem-ledger (2026-10-07) reads both tables of every tier schema of the root WHOLE; from mem-ledger 0f04624 on
(2026-10-11, P2's fba1346 includes it), the accounts above each schema's high-water mark and the reserve table whole. Only after the
tier's schema loaded: a tier that did not load merges nothing.

**ONE writer per root (STANDALONE)** — the guard above assumes it, and so does the money: the root's MemLedger is built STANDALONE
(`SwitchLedgerMaker`), the only writer of its package accounts and reserves. A second writer can be configured in two ways, and both are
refused by this rule, not by code: a second PROCESS serving the same root (two application instances on one root's switch database), or
another `ReplicationConfig.Mode`. Either would be two write-behind consumers for the same money, and a ghost reserve could no longer be
told from another writer's live one. Serve each root from ONE process.

**Every reload merges the root's new rows** (P1-J, ad-sphere P2c's G1). The only order a BSS can follow: prime-context's road makes the
tier (`tenant_created`), THEN the tier's accounts are written (a package purchase), the tree is rebuilt and the doorbell
`config_event_loader_<root>` rings. Before P1-J the tree showed the accounts and the ledger answered ACCOUNT_NOT_FOUND until another tier
came or the process restarted. Now every new tree passes the road's gate before it is shown and tells its root's ledger
(`LedgerPort.treeReloaded`): the port merges the rows written since its last look (`reloadNewEntities`, ghost reserves taken out), under
its write gate, at most ONE merge per `ledger.merge-spacing-sec` (default 5 s) — the first ring merges at once, on the reloading thread,
before the tree is shown; the rest of a burst become ONE merge at the end of the spacing, on the port's own daemon thread (an account
written in a burst reaches the ledger at most one spacing after its ring). Never on a session's thread: a session's money move waits for
a merge in progress (the write gate). Every reload counts — the ring, a `tenant_created`, a root served, and seed-context's backstop (every
5 min). The cost of one merge: the pinned mem-ledger (2026-10-07) reads both tables of every tier schema of the root WHOLE; from mem-ledger
0f04624 on (2026-10-11), the accounts above each schema's high-water mark and the reserve table whole.

**The switch ledger's own login** (P1-J, G3): `ledger.db {user, password-ref[, url, schema]}`, OPTIONAL, for a root whose ledger is the
switch ledger — its url and schema switch-db's where it says none; absent = switch-db's login, as before (no deployment changes on the
day). Why two logins: separation of duties at the database — prime-context grants the app's role (`ad_sphere`: the app's tables) and the
ledger's role (the money tables: `packageaccount`, `packageaccountreserve`) separately, and never one role for both ("a role is never
another's"); with the app's login the ledger's write-through was refused ("permission denied for table packageaccountreserve", P2c run 5).
ONE writer of money per root: the ledger's role. Its password, like switch-db's (P1-K), is read from the PROCESS environment only
(secreteer's env file), never the bootstrap's `secrets`. **Until prime-context accepts the keys (PC-0023, on the owner's word), a deployment document cannot carry
`ledger.db` — nor `ledger.merge-spacing-sec`** (prime-context's `DeploymentShape` refuses keys it does not know): switch-db's login and
the 5 s spacing apply. The shape is in the NEW sample `seed-sessionflow/src/test/resources/bootstrap/deployment-with-ledger-login.json`.

**What P2 must prove on its PostgreSQL lab** (this box has no database server: here the proof is MemLedger on H2 for the gate, the once rule
and the close, a stand-in for `addDatabase`):

1. the switch ledger of a root built over its tree's tier schemas (the root and `<root>_res_<id>`, nested) on PostgreSQL, the WAL in its
   directory;
2. a mem-ledger build that HAS `addDatabase` on that classpath (the pin moved, or the ad's own) — or the ERROR above, said once;
3. prime-context provisions a reseller tier (its `packageaccount` / `packageaccountreserve` tables committed), then sends `tenant_created`;
4. the application's road: the root's ledger loads exactly the new schema (`addDatabase`), the other tiers' cached accounts and open
   reserves untouched (a session of an old tier admitted and settled across the load), then the tree is reloaded;
5. a session of the new tier is admitted, reserved and settled on its own schema;
6. the same event twice = one load; a closed engine leaves no MemLedger thread.

## The two entities rule

The module registers exactly two entities — `PackageAccount` and `PackageAccountReserve` — in the MemLedger it is handed. `SwitchLedgers.over`
asks the ledger what it holds; any other entity is an `IllegalStateException` in words ("packagepurchase is never written by this module").
`Books` names only the two, so nothing here can write `packagepurchase`; a purchase's link moves through `Credit.reparent` on the account row,
as the call's `recharge` does.

## The account order (AccountOrder)

`fundable(partner, tree, now)` = the partner's package accounts, UNEXPIRED only (`PackageAccount.isExpiredAt(now)`, checked per request),
ordered TF_min / OTH_ea first, then BDT, then the rest — `ReserveBalanceStep.orderedAccounts`, moved with citation. `buckets(…)` = the unit
buckets ONLY — the owner: "if there is no package, no wifi". `cashOnly(…)` = BDT only, the call's international rule. Nothing here reads a
balance: the tree's rows are the catalog (id, purchase, unit, expiry); the live balance is the ledger's.

## The renewal of a window (WindowRenewal, the WiFi's)

The base's own renewal holds a whole window or refuses ("the base has no remainder"). The WiFi's `renewWindowSeconds(ctx, level)` calls
`WindowRenewal.renewSeconds(level, wholeAmount, reference, ratePerPeriod, periodSec)` instead — the call's C14 remainder
(`BalanceBillingService.reserveNextWindowSeconds`): the whole window through the base's reserve (held → the period; a fault → the period,
never a cut); refused → the live balance peeked; what is left, if it buys at least `MIN_FINAL_WINDOW_SEC` (1.0 s, the call's constant), is
held under the SAME reference and answered in seconds — the tracker enters WINDING_DOWN and arms the final cut; nothing left → 0. A zero
rate → the period, nothing held. Every reservation is RECORDED by the base's own chain: the flow hands `reserveWindow(ctx, level, amount,
reference)` (new on `SessionFlowSteps` = `AdmissionChain.reserveForRenewal`) as the lambda; this module records nothing itself.

## The H2 proof

The tests run a REAL MemLedger (no mock): H2 2.2.224 in MySQL mode, in memory, as the DataSource — the `EntityCacheLoader` loads
`SELECT * FROM schema.table` at the build; the WAL consumer writes `INSERT …`, `UPDATE … WHERE id = ?` (several per statement), `DELETE …`
and the offset's `INSERT … ON DUPLICATE KEY UPDATE` back, and the tests WAIT for H2 to show the settled balance and the row count 0
(`LedgerLab.await`). Chronicle lives in a temp directory, STANDALONE; nothing listens on a port (the lab says so first). `LedgerLab` is the
harness; `samples/LedgerFlow` is the WiFi-like session the base's `AdmissionChain` is walked with (the testkit's `TenantTreeBuilder`, which
gained `account(partner, id, purchase, uom, balance, expiry)`: the catalog row). Surefire opens the JDK for Chronicle on Java 21 with the
same `--add-opens` mem-ledger's own build uses.
