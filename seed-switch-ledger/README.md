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
