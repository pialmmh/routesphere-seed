# ARCH-0077-A — done: the switch ledger on the base (seed-switch-ledger), the DebounceGate cap, the TenantResolver SPI

**From:** the switch-ledger agent (worktree `routesphere-seed/worktrees/switch-ledger`, branch `switch-ledger` off `seed-context` ef35df2) · **To:** the architect
**When:** 2026-10-10, 21:28 → 22:12 +06 (the brief f76286f landed 21:26; every `date` below was read before it was written) · **Model:** Claude Fable 5.1

Everything in the brief and in both amendments is built, one commit per item, each pushed to `origin switch-ledger`; every rule was broken once
in a test and seen red before it was made green; the final suite ran on a CLEAN copy (`git archive` of the last code commit, a fresh private
repository chained read-only to `~/.m2/repository`, the whole reactor offline). Nothing of mine is running; no live environment, no WireGuard
address, no secret was touched; `~/.m2/repository` gained no file (checked: nothing newer than the brief). The merge is yours.

## 1 · The commits (oldest first), the branch head = the last line

| # | commit | item |
|---|---|---|
| 1 | `52f533e` | item 1 — the module `seed-switch-ledger` (parent list, BOM, pom, surefire openings for Chronicle on Java 21, H2 for the tests), `ModuleShapeTest`; the brief gains the "Amended" section (3b) |
| 2 | `6f67f16` | item 2 — `api/AccountOrder` (+ the testkit's `TenantTreeBuilder.account(…)`) |
| 3 | `011f18d` | item 3 — `internal/MemLedgerPort`, `internal/Books`, `internal/AccountLocks`, `internal/ClosedTiers`, `dependencies/SwitchLedgers`, `dependencies/SwitchLedgerSettings`, `api/SwitchLedger`, `api/LiveBalance`; `LedgerLab` (H2 + the real MemLedger), `samples/LedgerFlow`, `ChainOnTheLedgerTest`, `MemLedgerPortTest`; the brief gains the second amendment (the tier schema) |
| 4 | `632ff61` | item 3b — `api/WindowRenewal`; the base's `SessionFlowSteps.reserveWindow` (= `AdmissionChain.reserveForRenewal`, exposed); `WindowRenewalTest` |
| 5 | `a81af60` | item 4 — `internal/OrphanReaper`, `SwitchLedgers.reaper(…)`, `Books.returnRow` shared with the port's release; `OrphanReaperTest` |
| 6 | `c10e4de` | item 5 — `api/Credit`, `SwitchLedgers.credit(…)`; `CreditTest` |
| 7 | `ab18c4a` | item 6 — `internal/TwoEntities.check` at the build; `TwoEntitiesTest` (+ `OnlyAccounts`) |
| 8 | `4375030` | item 7 — `seed-config-client` `DebounceGate` gains the cap; `DebounceGateCapTest` |
| 9 | `2d02168` | item 8 — `seed-sessionflow` `spi/TenantResolver`, `api/RequestFacts`, `api/IdentificationRule`, `dependencies/TenantResolvers`, `dependencies/IdentificationRules`; `TenantResolverTest` |
| 10 | `57ac51d` | item 9 — the module README, the seed's README module list (the clean-copy suite ran on this commit) |
| 11 | (this file) | item 9 — the done report |

## 2 · What each item cites, and the decisions named

**Item 1.** The module `com.telcobright:seed-switch-ledger:1.0-SNAPSHOT`, package `com.telcobright.seed.switchledger`, in the parent's module
list and in `seed-bom`; depends on `seed-sessionflow`, `rtc-domain` 1.0-SNAPSHOT, `mem-ledger` 1.0.0-SNAPSHOT (read from `~/.m2/repository`
through the chained tail; Chronicle is bundled inside mem-ledger's jar, its pom's system-scoped Chronicle entries are not transitive), slf4j.
Tests: junit 5.10.2, assertj 3.27.3, slf4j-simple, H2 2.2.224 (the version `state-walk` already brings onto the base's classpath, so no
conflict). Surefire carries the `--add-opens/--add-exports` mem-ledger's own build and routesphere-core use for Chronicle on Java 21.
`ModuleShapeTest` guards the rule: the parent lists the module; `seed-sessionflow`'s pom declares neither `mem-ledger` nor `net.openhft`.
*A finding for you:* `rtc-domain`'s own pom depends on `mem-ledger` (for `CacheableEntity`), so mem-ledger's shaded jar — Chronicle classes
inside — already reaches `seed-sessionflow`'s classpath TRANSITIVELY today (`dependency:tree` of the base: `rtc-domain → mem-ledger`). The rule
I enforced is "the base gains no dependency" (no direct one); the transitive one is rtc-domain's business, not changed here.

**Item 2.** `api/AccountOrder`: `fundable` / `buckets` / `cashOnly` over `DynamicContext.getPartnerIdWisePackageAccounts()`, unexpired by
`PackageAccount.isExpiredAt(now)` — routesphere-core `ReserveBalanceStep.unexpired` (lines 187–207), the order `orderedAccounts` (159–185),
the BDT-only `bdtAccounts` (209–219), all moved with citation; the answers are copies, the tree untouched. The testkit's `TenantTreeBuilder`
gained `account(partnerId, accountId, purchaseId, uom, balance, expireDate)` — the catalog row at the context's map (additive; the base's
build and dependency list unchanged).

**Item 3.** `internal/MemLedgerPort implements LedgerPort, LiveBalance` over `com.telcobright.memledger.api.MemLedger`, built by
`SwitchLedgers.over(MemLedger, SwitchLedgerSettings, Clock)` → `api/SwitchLedger(port, balances, reaper, credit)` and `SwitchLedgers.memLedger(…)` → `LedgerPort`;
`SwitchLedgerSettings(reaperMaxAgeMinutes, reaperBatchLimit, lockTimeoutMs)`, `standard()` = (60, 500, 5000). The writes are
`PrepaidServiceWithCompensation`'s, line by line in `internal/Books`: the lock (35, 84–85), the live read (86–87), the balance check (94–102),
the consecutive reserve growing the one row (107–131), the first row (133–157), the debit (159–174), `returnBalance` (211–265), the row's
death (254–255, 704–713), `copyAccountMetadata` (695–702). The row is keyed by the TIER's reference (`<sid>#L<n>`); a window (`…#W<n>`) grows
it; idempotency is by the full reference, in the process, forgotten when the tier closes. Settle: `reservedOf(level) − charged` in either
direction, the row deleted, `TierSettlement.of(level, charged, balanceAfter)`; a second settle answers the first (`ClosedTiers`, bounded).
Release: the whole row back. A MemLedger failure (no answer, a refused write, a lock not free in `lockTimeoutMs`) = `LedgerFault`.
Decisions named: (a) the account is `level.getPackageAccountId()` — the tree's `PackageAccount` from `TierRate.account`, else the account an
O4 rotation named in `chargeAccountId` (the rotation's fresh level carries no `PackageAccount`); none → `LedgerRefusal("NO_ACCOUNT")` as the
brief says; (b) an account the schema does not hold → `LedgerRefusal("ACCOUNT_NOT_FOUND")` (not in the brief; a refusal with a code of its own);
a schema the ledger does not SERVE → `LedgerFault` in words (the host must register every tier schema); (c) a settle with no open row
(settled, released or reaped before) moves nothing and answers `TierSettlement.owed(…)` in words — the brief's "never owed" holds for every
normal settle; this is the abnormal path, said once; (d) the lock table is one per MemLedger (`AccountLocks.of(ledger, timeout)`, a weak
identity map), so a credit and a settle share it however the doors are wired; (e) the reserve row's name is `SESSION-Reserve-<key>`.

**Item 3b (the amendment).** `api/WindowRenewal.renewSeconds(level, wholeAmount, reference, ratePerPeriod, periodSec)` —
`BalanceBillingService.reserveNextWindowSeconds` (86–120): the whole window first (held → the period; a fault → the period, never a cut);
refused → the live balance peeked (`LiveBalance.balanceOf`, a read, no lock); nothing left → 0; `seconds = remaining / rate × period`; below
`MIN_FINAL_WINDOW_SEC` — the constant at line 37, value **1.0** — → 0, nothing held; else exactly the remainder under the SAME reference, those
seconds answered. A zero rate → the period, nothing held (line 89). The base's `SessionFlowSteps` gained
`protected final String reserveWindow(C ctx, LevelAdmission level, BigDecimal amount, String reference)` = `AdmissionChain.reserveForRenewal`
(exposed through a package-private abstract, exactly as `renewThroughLedger` is), so the chain's `recordReserve` records every reservation —
the module records nothing. The sample `LedgerFlow` renews through it when handed the peek (the WiFi's shape).

**Item 4.** `internal/OrphanReaper.reap(dbName)` — `reapOrphanReserves` (533–576): rows older than the max age (548–555), an unreadable time
never (550–554), the batch limit (547), the normal return path (`Books.returnRow`, shared with the port's release); under the account's lock
the row is read again before it is given back. `SwitchLedgers.reaper(…)` for the host's schedule, one call per tier schema.

**Item 5.** `api/Credit.credit(dbName, accountId, delta, reference)` and `reparent(dbName, accountId, newPurchaseId)` — `recharge`
(282–382; the reparent 298–316, 347–351): a positive DELTA on the live balance under the port's own lock; a delta ≤ 0 or an unknown account
refused in words. `SwitchLedgers.credit(…)`. The WiFi does not call it at go-live.

**Item 6.** `internal/TwoEntities.check(MemLedger)` at `SwitchLedgers.over`: the ledger's `getStats().getCacheStatistics()["cache_contents"]`
names what it holds per schema; anything beyond `PackageAccount` and `PackageAccountReserve` → `IllegalStateException` ("… the MemLedger handed
in also holds [PackagePurchase]: packagepurchase is never written by this module; build the ledger with the two entities only"); one of the
two missing → refused too. `Books` names only the two entities.

**Item 7.** `seed-config-client` `DebounceGate`: when the last FIRE is older than 2 × debounceMs a ring fires at once, else it re-arms —
`ConfigEventConsumer.scheduleReload` (560–601). Decision named: the gate is born as if it had just fired (the product has just loaded its
config), so a burst right after the start still collapses to one trailing fire and the four old tests stay green; `ConfigEventConsumer`'s
`lastReloadTimeMs` starts at 0 (its first event is immediate) — that is the one difference, on purpose. The cap test rings every 1 s for 30 s
with 3 s of debounce, as the brief asks: it takes 34 s.

**Item 8.** `seed-sessionflow`: `spi/TenantResolver` (`Optional<String> tenantOf(RequestFacts)`), `api/RequestFacts(kind, match)` with
`listen` (the LOCAL `ip:port`) and `esl` (`host:port`) and the factories `listen(ip, port)` / `esl(host, port)`, `api/IdentificationRule(kind,
match, tenant)`, `dependencies/TenantResolvers.ofRules` (exact match on (kind, match); no match = empty, never a default; a duplicate (kind,
match) = `IllegalArgumentException` naming both rules) and `none()`, `dependencies/IdentificationRules.fromBootstrap(Map)` (the key
`identification`; absent = no rules; a malformed entry refused in words). The product sets `ctx.tenantName` from the resolver before
admission; the base's `resolveTenant` is unchanged.

**Item 9.** The module README (the structure, the three verbs, the tiers are SCHEMAS — register every tier schema in
`MemLedgerBuilder.databases(...)`, explicit names from the tree, preferred over `autoDiscoverDatabases` — the two entities rule, the account
order, the window renewal, the H2 proof); the seed's README lists the module. There is no `docs/architecture` page in the seed (only
`docs/DESIGN.md` and `docs/agents`), so none gained a line.

## 3 · Every red seen (a rule broken once, then made green)

| item | how the rule was broken | the red, by name |
|---|---|---|
| 1 | `mem-ledger` added to `seed-sessionflow`'s pom | `ModuleShapeTest.theBaseDeclaresNeitherMemLedgerNorChronicle` — "[seed-sessionflow's dependencies] not to contain mem-ledger" |
| 2 | a naive first cut: the raw list, no expiry, no order, cash among the buckets | `AccountOrderTest.fundable_isUnexpiredOnly_bucketsFirst_thenCash_thenTheRest`, `buckets_areTheUnitBucketsOnly_noCash`, `cashOnly_isBdtOnly` — all three "to contain exactly (and in same order)" |
| 3 | eight rules broken at once in a patched build: no balance check · no idempotency · a row per window · an overrun not debited · the row surviving the settle · a release giving nothing back · a failure as a refusal · no NO_ACCOUNT | 11 failures: `MemLedgerPortTest.aBalanceShortOfTheAmount_isEmpty`, `aRepeatedReference_answersTheFirstResult_andDebitsOnce`, `release_givesTheWholeRowBack`, `aLedgerThatDoesNotAnswer_isAFault_neverARefusal`, `noAccount_isTheRefusalNO_ACCOUNT_inWords`; `ChainOnTheLedgerTest.aLaterTiersRefusal_releasesTheLeaf`, `aRenewalTheBucketCannotFund_isRefused` ("the third window: 20 < 40"), `renewals_growTheOneRow` ("one row per tier, grown per window"), `settle_debitsAnOverrun`, `settle_returnsReservedLessCharged` ("packageaccountreserve after the settle"), `aLedgerThatDoesNotAnswer_isBILLING_SYSTEM_ERROR` |
| 3 | the unregistered-schema answer (found while writing, not staged): a refusal where a fault was asserted | `MemLedgerPortTest.anUnknownSchema_isAFault` — "expecting LedgerFault but was LedgerRefusal: ACCOUNT_NOT_FOUND … not held in schema res_9" → `Books.serves(db)` made it a fault |
| 3b | a first wrong cut (a remainder held however small; a zero rate asked) | `WindowRenewalTest.aZeroRate_answersThePeriod_andHoldsNothing`, `belowTheMinimumFinalWindow_answersZero_andHoldsNothing` |
| 3b | a second wrong cut (the whole window or nothing — no remainder) | `theRemainder_isHeldUnderTheSameReference_andAnsweredInSeconds`, `twoAndAHalfWindowsLeft…` ("5 left of a 10 window: 30 s"), `exactlyTheMinimum_isHeld`, `aZeroRate…` |
| 4 | every row an orphan whatever its age | `OrphanReaperTest.anOldRowIsGivenBack_…_aYoungRowUntouched` ("expected 1 but was 2"), `theYoungRowBecomesAnOrphanWhenItAges`, `theBatchLimitBoundsOnePass` |
| 5 | no lock, the delta written AS the balance | `CreditTest.aDeltaLandsOnTheLiveBalance_whileAReserveIsOpen`, `theCreditWaitsForTheAccountsLock` ("the credit waits while a verb holds the account") |
| 6 | no check at the build | `TwoEntitiesTest.aThirdRegisteredEntity_isRefusedAtTheBuild_inWords`, `aLedgerMissingOneOfTheTwo_isRefusedToo` |
| 7 | the old gate (a pure trailing-edge debounce) | `DebounceGateCapTest.aDenseStream_firesAboutEveryTwiceTheDebounce` — "fires during 30 s of rings every 1 s = 0" |
| 8 | no match answered the first rule's tenant; duplicates passed | `TenantResolverTest.anUnknownAddress_isEmpty_neverADefault`, `twoRulesOnOneKindAndMatch_areRefusedInWords` |

## 4 · The H2 proof, in one paragraph

The tests build a REAL `MemLedger` (`MemLedger.builder()…build()`, STANDALONE, no REST, no gRPC) over H2 2.2.224 in MySQL mode
(`jdbc:h2:mem:…;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE`) with one SCHEMA per tier (`btcl`, `res_2`) and the two
tables; the `EntityCacheLoader` loads `SELECT * FROM schema.table` at the build; mem-ledger's own `wal_consumer_offset` (`CREATE TABLE … INDEX
…`, `INSERT … ON DUPLICATE KEY UPDATE`) and the persister's extended `INSERT`, multi-statement `UPDATE … WHERE id = ?; UPDATE …`, and `DELETE`
all ran on H2; the tests WAIT (`LedgerLab.await`, ≤ 15 s) for H2 to show the written-behind balance, the reserve unit, the purchase id and the
row count 0 after a settle. Chronicle's queue lives in a temp directory per lab, deleted at the close (best effort). Nothing listens on a
port; the lab prints that line first.

## 5 · The clean-copy suite

`git archive 57ac51d` (259 Java files) into a fresh directory; built OFFLINE with a FRESH private repository (`-Dmaven.repo.local=$HOME/.m2/switch-ledger-clean-repo
-Dmaven.repo.local.tail=$HOME/.m2/repository`, nothing installed into the shared one); the whole reactor, `mvn -o test`; 22:07:25 → 22:09:29 +06;
**BUILD SUCCESS**, every module:

| module | tests run | failures | errors | skipped |
|---|---|---|---|---|
| seed-config | 5 | 0 | 0 | 0 |
| seed-config-client | 5 | 0 | 0 | 0 (the cap test included, 34 s) |
| seed-context | 20 | 0 | 0 | 1 (a Kafka IT) |
| seed-campaign | 67 | 0 | 0 | 0 |
| seed-routing | 33 | 0 | 0 | 2 (ITs) |
| seed-sessionflow | 177 | 0 | 0 | 1 (the Kafka IT; 172 before + the 5 of item 8) |
| seed-tenant | 11 | 0 | 0 | 0 |
| seed-idgen | 19 | 0 | 0 | 0 |
| seed-switch-ledger | 44 | 0 | 0 | 0 (shape 3 · AccountOrder 5 · MemLedgerPort 10 · ChainOnTheLedger 9 · WindowRenewal 7 · OrphanReaper 3 · Credit 4 · TwoEntities 3) |
| **all** | **381** | **0** | **0** | **4** |

The base's own suite was also run alone in the worktree after the `reserveWindow` change (172/0/0/1) and before item 8. The done-report commit
after 57ac51d adds this markdown only.

## 6 · What I could not do, and the open points for you

- **The ad carries Chronicle through rtc-domain today** (above, item 1): `rtc-domain → mem-ledger` (compile scope, for `CacheableEntity`).
  If the rule is meant for the classpath and not only the pom, rtc-domain must shade or split `CacheableEntity` — not mine to change.
- **No `docs/architecture` page exists in the seed**; the seed's README carries the module line instead.
- `AccountLocks` is one table per MemLedger behind a static weak identity map (so every door shares it whatever the wiring); if you would
  rather the sharing be explicit only (the `SwitchLedger` bundle), the static map is one method to drop.
- The reserve row's `name` is `SESSION-Reserve-<key>` (the call writes `CALL-Reserve-<uuid>`); a product word there would need a setting the
  brief's `SwitchLedgerSettings` shape does not carry.
- I never cd'd into another worktree or the main checkouts; the cited repositories were read only. The private repositories
  `~/.m2/switch-ledger-repo` (the work) and `~/.m2/switch-ledger-clean-repo` (the clean run) are mine to delete when you say.
