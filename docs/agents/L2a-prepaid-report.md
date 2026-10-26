# L2a — `seed-prepaid`: the call's money as a seed feature — the report

**Round:** L2a (brief `docs/agents/L2a-prepaid-brief.md`, commit 09e60c6) · **branch** `prepaid` off `routesphere-flow` cb0e840 ·
**to** the architect · **written** 2026-10-27 02:4x +0600 · items 0–1 by the first agent (stopped at 2a for a model switch), 2a–6 by the
resuming agent (Claude Opus 5.5). Nothing pushed. Nothing left running.

## 0 · In short

| # | Commit | What | Whole reactor from clean (run · fail · error · skip) |
|---|---|---|---|
| — | cb0e840 | the base (the architect's number) | 698 / 0 / 0 / 3 |
| brief | 09e60c6 | the brief | — |
| 1 | 623e12a | the parity suite FIRST, on the base: one test per row P1–P21 on the REAL switch ledger | 719 / 0 / 0 / 9 (a git-archive copy) |
| 2a | 368a6c3 | the move-only batch: the base LOSES every money step, seed-prepaid holds the ONE engine, its maker by ServiceLoader | 724 / 0 / 0 / 9 |
| 2b | 222807a | the Prepaid builder + its verbs over the moved steps; the call's Prepaid closes the six gaps | 752 / 0 / 0 / 9 |
| 3 | 8bcbd8e | ONE engine behind `RoutesphereApp`: `Builder.prepaid(prepaid)`; a lane without one unchanged | 777 / 0 / 0 / 9 |
| 4 | e6dff43 | CHARGE_NOW — the SMS's final debit, every tier at admission, no hold row | 785 / 0 / 0 / 9 |
| 5 | 8f0974e | `prepaid.admin()`: credit, reparent, accounts; purchase refused naming L2b | 790 / 0 / 0 / 9 |
| 6 | (this) | this report | — |

**The code head 8f0974e on a git-archive clean copy:** **790 / 0 / 0 / 9**, exit 0, every module as in the worktree run (`~/l2a-work/after-8f0974e-clean.log`). The 9 skipped =
the base's 3 (seed-context 1, seed-routing 2, as at cb0e840) + the 6 gap rows kept `@Disabled` on the BASE lane (they run and pass on the
two Prepaid lanes). **statewalk 4.1.0 only** — `dependency:tree -Dincludes=com.telcobright:statewalk,com.telcobright:state-walk,com.telcobright:statewalk-v2` on the clean copy: `com.telcobright:statewalk:jar:4.1.0` in the six modules that use it (seed-sessionflow, seed-bootstrap-config, seed-switch-ledger, seed-prepaid, seed-tenant: compile; seed-sessionflow-samples: test), no state-walk v1, no statewalk-v2 (`~/l2a-work/after-statewalk-tree.log`).

**ad-sphere needs ONE line** (L3's; the architect adds it in the gate copy), beside its other seed modules in its `pom.xml`:

```xml
<dependency><groupId>com.telcobright</groupId><artifactId>seed-prepaid</artifactId><version>${seed.version}</version></dependency>
```

Without it every ad-sphere engine start is refused in words: `[AdSupervisor] no money engine on the classpath: seed-prepaid is not on the
classpath — add it (…); the base never moves money itself`. Its code and imports stay as they are: it overrides none of the moved hooks
(`reserveBalance`, `finalizeTier`, `renewWindowSeconds`), its `TierRate.held` dry run is the moved `takeHeld`, and its own `LedgerPort`
implementations inherit the port's five new verbs as defaults (refused in words if ever asked).

## 1 · The map (item 0): every money step of the seed → the live call line it ports → its Prepaid verb

Before L2a every step below lived in `seed-sessionflow`; after 2a each is ONE call into the engine (`flow.money().x`) and the step itself
lives in `seed-prepaid/internal`, moved as it was (`~/l2a-work/tools/moveproof.py`: every line of the moved code is found verbatim in the
base at 623e12a, except the class headers and the mechanical lines of §3.1).

| Seed step (cb0e840) | Where now | Live call (routesphere-core / statewalk-v2-call) | Verb |
|---|---|---|---|
| `AdmissionChain.rateAndReserve` + `reserve` + `recordReserve` — the tier rated by the lane, ONE unit held on ITS account under `<sid>#L<tier>` | `TierReserve` | `ReserveBalanceStep.doExecute` 63–135; `PrepaidServiceWithCompensation.reserveBalance` 78–184 | admit |
| `AdmissionChain.takeHeld` — the lane's own step held the tier in one move (`TierRate.held`) | `TierReserve` | `ReserveBalanceStep`: "attempting the reserve IS the affordability test" | admit |
| `AdmissionChain.ledgerFault` — a thrown ledger error is `BILLING_SYSTEM_ERROR`, never a balance cause | `TierReserve` | `ReserveBalanceStep` 103–121; `DefaultAdmissionService` 277–283 | admit |
| `AdmissionChain.notAsked` — no time left in the admission budget: the ledger not asked | `TierReserve` | — (the seed's own admission budget) | admit |
| `AdmissionChain.undo` → `release` per tier — a refused candidate gives every hold back | `PrepaidEngine.release` → `TierReserve.release` | `DefaultAdmissionService.compensateReserves` 637–668 | admit (refusal) |
| `AdmissionChain.journalReserve` / `journalRelease` — the journal of the sessions in the air (F9) | `TierReserve` | — (the seed's own; the live has only the 30-min reaper) | admit · finish |
| `RoutesphereFlow.renewReserves`' loop `narrowestWindow` — every tier renews, the narrowest decides | `PrepaidEngine.renewWindow` | `BalanceTracker.renewOrSignal` 224–250 | renewWindow |
| `AdmissionChain.renewThroughLedger` — the next window, whole, under `…#W<n>`, else rotation or the cut | `TierReserve` | `BalanceBillingService.reserveNextMinute` 49–70 (the remainder, 86–120, only through a subclass) | renewWindow |
| `SpanRotation` — O4: the account rotated at a renewal (the WiFi's B17) | `internal/SpanRotation` | — (not in the live call) | renewWindow |
| `RoutesphereFlow.reserveWindow` → `reserveForRenewal` — a lane's own window | `TierReserve` | — | renewWindow |
| `SessionSettlement.settle` + the default `finalizeTier` — delivered: rate × billed units, the rest back, both ways; else the refund rule | `internal/SessionSettlement.settleTier` | `BalanceBillingService.finalizeBalance` 127–148, `settleAgainstReserve` 176–197 | finish |
| `SessionSettlement.release` — never answered: every hold back (or the refund rule charges an offered one) | `internal/SessionSettlement` | `BalanceBillingService.refundBalance` 203–219 | finish |
| `RoutesphereFlow.chargeByTheRefundRule` | `internal/SessionSettlement` | — (the seed's refund rule, ad / SMS) | finish |
| `SessionCdr.closeAsReserved` (A2) / `giveBack` (F9) — the restart's money | `internal/LeftoverMoney` | — (the live: `ReserveReaperJob` only) | finish (restart) |
| switch ledger `MemLedgerPort` reserve / settle / release, `Books`, `AccountLocks` | unchanged (`seed-switch-ledger`) | `reserveBalance` 78–184, `returnBalance` 211–265 | all |
| switch ledger `OrphanReaper` | unchanged | `ReserveReaperJob` + `reapOrphanReserves` 533– | (the host's schedule) |
| switch ledger `Credit` | unchanged; behind `admin().credit/reparent` (item 5) | `recharge` 294–383 | admin |
| — | NEW (item 4) `LedgerPort.charge/refund`, `Books.debit` | `debit` 461–516 (javadoc 441); `BalanceCheckRule.refund` 236–255 | admit (CHARGE_NOW) · finish |
| — | NEW (2b) the account walk, the remainder window, the Final | `ReserveBalanceStep` 82–123; `reserveNextWindowSeconds` 86–120; `finalizeBalance` 127–148 | admit · renewWindow · finish |

**Where the seed differs from the live call** (findings, reported — the live is not touched):

| # | The seed | The live | Note |
|---|---|---|---|
| D1 | answers-only lane: ONE account per tier (the one `rateAtLevel` names) | every fundable account tried until one pays (`ReserveBalanceStep` 82–123) | P2's gap; closed by the Prepaid |
| D2 | answers-only lane: the no-balance cause is per session (`noBalanceCause`) | `NO_BALANCE_INT_OUT` for any international shortfall (`DefaultAdmissionService` 284–286) | P3's gap; closed by `refusalCause` |
| D3 | answers-only `RoutesphereApp` lane: whole windows only | the whole unit, else the remainder in seconds (`reserveNextWindowSeconds` 86–120) | P9/P10's gap; closed by HOLD_WINDOW |
| D4 | answers-only: the admission rate charged, rounded to `max(2, scale)` | re-rated on the terminating number, six decimals (`finalizeBalance`, `calculateCharged` 225–240) | P13/P14's gap; closed by `Final.charge(cost)` |
| D5 | a Prepaid lane's CDR keeps the ADMISSION rate on the level (`callRatePerMinBDT`) | `finalizeBalance` sets the re-looked-up rate on the level | money identical; the CDR's rate field differs (L2b) |
| D6 | a Prepaid lane's tier has no matched prefix on the CDR (the rate rule answers an amount only) | `matchPrefixCustomer` from the rate row | L2b: a `ratePrefix` rule |
| D7 | an admission budget: ADMISSION_TIMEOUT, the ledger not asked when no time is left | no budget | the seed's own, kept |
| D8 | the journal of the sessions in the air closes a dead process's sessions at the next start | the 30-min reaper only | the seed's own, kept |
| D9 | a settlement the ledger refuses is OWED: counted, one ERROR, the CDR says it | `settleAgainstReserve` logs the error; the CDR charges | the seed's own |
| D10 | CHARGE_NOW charges EVERY tier as the chain walks it | the SMS charges only the LAST tier of the chain, the others checked (`BalanceCheckRule.chargeTenantChain` 187–213) | the design's table; **the SMS port needs the owner's word** (port-as-is) |
| D11 | CHARGE_NOW: the ledger only; an account it does not hold is `ACCOUNT_NOT_FOUND` | `NOT_HELD` → a guarded MySQL update (`debitInDatabase`) | the owner's rule: package money through mem-ledger |
| D12 | CHARGE_NOW idempotent per reference (bounded memory), a refund once per tier | no idempotency on `debit`; the refund is a `recharge` with `sms-refund:<ref>` | L2b: the reference row in the same unit |
| D13 | one renewal per tier as the chain walks; a refused tier → rotation (answers-only) or the remainder (Prepaid) | the same per tier; the renewal's give-back is a TODO (`EslCallRegistry` 1130–1136) | as-is (design §7 decision 4) |

## 2 · The gap list (item 1's disabled rows)

Each was seen red on cb0e840 first (`~/l2a-work/item1-red-run.log`), stays `@Disabled("gap: …")` on the BASE lane, and — forced on with
`-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition` at 2b — is exactly the six reds there (`2b-gaps-on-base-red.log`).
On the two Prepaid lanes (a `RoutesphereFlow` subclass with `moneyRules()`, 2b; `RoutesphereApp…prepaid(CallPrepaid)`, 3) all six run
and pass, with the SAME assertions.

| Row | Live | What the base (answers only) lacks | Closed by (the Prepaid) |
|---|---|---|---|
| P2 | `ReserveBalanceStep` 159–219, 82–123: unexpired only, packages before cash, each account tried until one pays | holds on the ONE account `rateAtLevel` chose: a refused package is `INSUFFICIENT_BALANCE`, the cash never tried | the account walk (`AccountWalk` → `TierReserve.holdOnTheFirstThatPays`) over `AccountRule` (`AccountOrder`) |
| P3 | `ReserveBalanceStep` 68–80, `bdtAccounts` 209–219; `DefaultAdmissionService` 284–286 | no per-tier refusal cause: a cash shortfall abroad is `INSUFFICIENT_BALANCE` | `chooseAccounts` (cash only abroad) + `refusalCause` (`NO_BALANCE_INT_OUT`) |
| P9 | `BalanceBillingService.reserveNextWindowSeconds` 86–120 | no `renewWindowSeconds` hook in `RoutesphereApp`: the remainder answers 0 | HOLD_WINDOW: `WindowedRenewal` over the switch ledger's `WindowRenewal` and the port's new peek `balanceOf` |
| P10 | `BalanceTracker.renewOrSignal` 224–250; `CallSupervisor.scheduleFinalCut` 907–925 | no partial window: the cut at the tick | the same (a partial window → the base's budget child arms the cut at its end) |
| P13 | `BalanceBillingService.finalizeBalance` 127–148 (re-rated on the terminating number; abroad the root's 117 rate) | no cost-per-tier hook: the admission rate is charged | `Final.charge(tier -> cost)` with the call's own cost (`CallPrepaid.costAtTheEnd`) |
| P14 | `calculateCharged` 225–240 (six decimals; abroad minimum time + pulse) | `chargeAtSettle` rounds to `max(2, rate.scale())`: 0.62, not 0.616667 | the same |

**Three corrections of the rows' SCENES (never their rule), found when the disabled rows first ran (2b):**
1. `ParityScene.answer` now delivers the answer as the library does — on the context, then the flow's `onEstablished` (the lane's
   `onAnswered`): before it, a handler-level answer never told the lane, so P13's ported number (its premise) never happened.
2. P10 runs on a 1 s / 2 s cadence (was 0 / 1 s): with a 1 s window no partial window can exist — a remainder is less than one unit, so less
   than 1 s, and P9's live floor `MIN_FINAL_WINDOW_SEC` = 1 s makes it "nothing left". The bounds moved with it ([3700, 4900] ms partial,
   [2900, 3950] ms now).
3. P14's cash case dials `NOT_PORTED` (8801712…, Direct's 0.75): the scene ports `DOMESTIC` (P13's), which re-rates it at 0.50.

## 3 · Per item: the files, the rules, the reds

### 3.1 · 2a — the move-only batch (368a6c3)

**The base gains** `spi/MoneyEngine<C>` (admit · release · renewWindow · renewTier · hold · finish · settleTier · closeAsReserved ·
giveBackLeftover), `spi/MoneyEngineMaker` (`ServiceLoader`, `NONE` refuses in words), `api/LaneAnswers` + `api/FlowAnswers` (the lane's
protected hooks and the base's guards through ONE public door, `flow.answers()`), `RoutesphereKit.moneyMaker()` / `Builder.money(...)`,
`RoutesphereFlow.money()` (made once by the kit's maker), the engine's start refusing a flow without one.

**The base loses** (each now `money().x`): `AdmissionChain`'s hold, give-back, journal, renewal (→ `TierReserve`); `RoutesphereFlow`'s
default `finalizeTier` body, `chargeByTheRefundRule`, `narrowestWindow` (→ `SessionSettlement.settleTier`, `PrepaidEngine.renewWindow`);
`SessionSettlement`, `SpanRotation` (git mv); `SessionCdr.closeAsReserved` / `giveBack` / `levelOf` (→ `LeftoverMoney`). The grep at the
head (`~/l2a-work/3-grep-base-money.txt`): no `.reserve(` / `.settle(` / `.release(` of money left in `seed-sessionflow` main but
`PerRootLedger`'s delegation of the port (it IS a port) and the channel slot's release; every money step is `money().x`.

**Mechanical changes allowed in the move (no logic change), each one:**
1. `ctx.walking.add(level)` → `ctx.walked(level)` — a new public `RoutesphereContext.walked(LevelAdmission)`; the walk stays package-private.
2. `ctx.trying` → `ctx.candidateBeingAdmitted()` (the existing public accessor).
3. `RoutesphereFlow.log` (protected) → each moved class's own logger of the SAME name (`LoggerFactory.getLogger(RoutesphereFlow.class)`).
4. `SessionCdr.of(flow).lostRecordsOf(ctx)` → `flow.lostRecordsOf(ctx)`; `rootDbOf(ctx)` → `flow.rootDbOf(ctx)` (the LaneAnswers door).
5. `AdmissionChain.of(flow).journalRelease | reserveForRenewal` → `TierReserve.of(flow)…`.
6. The moved classes' field `RoutesphereFlow<C> flow` → `LaneAnswers<C> flow`; the moved `finalizeTier` / `chargeByTheRefundRule` bodies
   qualified (`delivered(…)` → `flow.delivered(…)`, `kit.ledger()` → `flow.kit().ledger()`).
7. Visibility: `TierReserve.release`, `LeftoverMoney.giveBack` / `closeAsReserved` private → package-private.
8. The F9 javadoc that sat above `closeAsReserved` (misplaced) now sits above `giveBack`.

**The tests moved, unchanged.** A test that runs a session needs the engine, and `seed-sessionflow` / `seed-switch-ledger` cannot
test-depend on `seed-prepaid` (a reactor cycle, test scope included). So 34 test classes of `seed-sessionflow` (+ the fixtures
`ArmedTree`, `VersionedTrees`) and 3 of `seed-switch-ledger` (`ChainOnTheLedgerTest`, `SwitchLedgerOnTheBootstrapKitTest`,
`WindowRenewalTest` + `LedgerFlow`) moved to `seed-prepaid/src/test`, IN THEIR OWN PACKAGES (100 % renames); they read the base's fixtures
(`Lab`, `LabFlow`, `BootstrapWorld`, …) from `seed-sessionflow`'s new test-jar. `seed-sessionflow-samples` gained the seed-prepaid test
dependency. Per class: every one of the 152 classes of 623e12a runs after with the SAME counts (37 in another module), + 2 new.
Two consequences: `HotPathHasNoListScanTest` (stays in the base) scans `seed-prepaid`'s main sources too, its allow-list entry
`SessionCdr#closeAsReserved` → `LeftoverMoney#closeAsReserved`; `seed-prepaid` carries `simplelogger.properties` at info (the base's
default) because `seed-switch-ledger`'s test-jar carries one at warn (`NoForgedLogLinesTest` reads the step lines).

**New tests:** `NoMoneyEngineOnTheClasspathTest` (4, seed-sessionflow) · `TheMakerOnTheClasspathTest` (1, seed-prepaid). **Reds:**

```
2a-red-M1 (no start check):        NoMoneyEngineOnTheClasspathTest.theEnginesStart_isRefusedInWords — FAILURE (the engine started)
2a-red-M2 (NONE answers null):     aFlowWithoutAnEngine_isRefusedInWords:73, theEnginesStart_isRefusedInWords:84 — FAILURE
2a-red-M3 (no services file):      TheMakerOnTheClasspathTest FAILURE; CallParityAdmissionTest 1 F 5 E: "[CallSupervisor] no money engine on the
                                   classpath: seed-prepaid is not on the classpath — add it (…); the base never moves money …"
```

### 3.2 · 2b — the builder and its verbs (222807a)

`api/Prepaid.<C>builder(name)` exactly as design §10: `admission` ■, `window` + `firstRenewal` (HOLD_WINDOW ■; the kit's own cadence or
refused), `rate` ■, `finalize` ■, `accounts`, `chooseAccounts`, `refusalCause`, `beforeHold`, `onExhausted`, `onLowBalance`,
`onFinished`, `hooks(PrepaidHooks)`, `reference`, `settings(PrepaidSettings)`; `build(kit)` refuses every missing part in ONE sentence
naming each; sealed once built; a Prepaid serves the kit it was built on; `Prepaid.ofTheLanesAnswers(kit)` for a lane that gives none.
`api/Final` (`charge(tier -> cost)` · `releaseAll` · `refundAll` · `nothing`), `api/Admission`, `api/AccountRule`, `api/PayingTier`,
`spi/PrepaidHooks` (every callback with a default), `dependencies/PrepaidSettings` (values only, R0).

The engine over the rules (the moved steps untouched): `AccountWalk` (the first account that pays, through the moved reserve; a dry run
rates the first), `WindowedRenewal` (one unit per window, the whole else the remainder), `FinalMoney` (the lane's Final per tier through
the port's settle), `LaneHooks`, `LowBalanceMark`. A lane with rules finishes by its finalize answered or not, then `onFinished` once;
`onExhausted` when a renewal finds the money ending. The base gains `spi/MoneyRules`, `RoutesphereFlow.moneyRules()`,
`LaneAnswers.moneyRules()`, `LedgerPort.balanceOf` (default empty; `PerRootLedger` delegates; `MemLedgerPort` already had it).

Parity: the 21 rows ALSO on the call as a BIG lane (`CallFlowOnPrepaid`) with `CallPrepaid` (design §10.1's call builder). **Reds:**

```
2b-gaps-on-base-red (the base lane, gaps forced on): P2:59 "p2 was refused: INSUFFICIENT_BALANCE", P3:83, P9:87 "[the remainder: 0.5 min = 30 s]",
    P10:116 "[cut when the remainder ended, before the third tick (5 s)]", P13:38 "[one minute at the ported number's rate]", P14:66 — and only those
2b-red-mutants: no next account → P2 · refusalCause ignored → P3 · no remainder → P9, P10 · the lane's cost ignored → P11, P12, P13, P14, P16 ·
    no cadence check → PrepaidBuilderTest.aWindowedPrepaid_statesTheKitsOwnCadence, aPerEventPrepaid_holdsNoWindow
```

### 3.3 · 3 — ONE engine behind RoutesphereApp (8bcbd8e)

`RoutesphereApp.Builder.prepaid(MoneyRules<C>)`: the Prepaid answers the rate, the accounts, the renewal and the end — `rateAtLevel` and
`refundPolicy` no longer required of that lane; `rateAtLevel`, `refundPolicy`, `settleOutcome`, `billedUnits`, `rateNextWindow` given beside
it refused by name ("two answers to one question"). A lane that gives none is unchanged: the maker builds `Prepaid.ofTheLanesAnswers` —
the SAME engine class. The flow calls admit at admission, renewWindow from the budget child, finish at the end (the grep, §3.1). The 21
rows a third time on `CallLane.onPrepaid` (builder syntax). `RoutesphereAppPrepaidTest` (4). `seed-sessionflow-samples` (ad-sphere's
seed-facing samples) 76/0/0/0 then, 78/0/0/0 at the head. **Reds:** two answers allowed → the refusal test; the lane's Prepaid ignored →
its engine test and all 8 admission rows of the builder lane (`INTERNAL_ERROR`: the lane has no rateAtLevel).

### 3.4 · 4 — CHARGE_NOW (e6dff43)

The port gains `charge(level, amount, reference)` (`debit` 461–516: short → nothing taken, zero → no write, else debited, NO reserve row)
and `refund(level, amount, why)` (`BalanceCheckRule.refund` 236–255), defaults refused in words; `PerRootLedger` delegates; `Books.debit`
(489–501, `lastAmount` = −amount — every write still in Books); `MemLedgerPort` implements both (a charge idempotent per reference,
`Charges`, bounded as `ClosedTiers`; a refund once per tier). The engine: a CHARGE_NOW tier charged on the first account that pays as the
chain walks it (not journaled: a charge is final); a refused candidate's charges given back (`refundCharge`, OWED in words if refused);
`FinalMoney`: nothing → stands, refundAll / releaseAll → back whole, charge(cost) → back down to the cost (above it = OWED, never taken).
`SmsParityTest` (6, `SmsLane` = design §10.1's sms builder) and the samples' `PrepaidBuilders` (call- and SMS-shaped, design §10.1) +
`PrepaidBuildersTest` (2, dry runs). **Reds:** a debit that leaves a hold row → `S1…:43 [no hold row]`; a refund that skips a tier →
`S2…:67`.

### 3.5 · 5 — admin() (8f0974e)

`api/PrepaidAdmin` (`prepaid.admin()`): `credit(root, tierDb, accountId, amount, reference)` — a delta through the kit's port under the
account's lock over the switch ledger's EXISTING `Credit` (`recharge` 294–383); `reparent(…, purchaseId)` (347–351); `accounts(root, tierDb,
partnerId)` — as the tree names them today (S4 stays), expired ones included, each with the ledger's live balance; `purchase(…)` — refused
in words naming L2b (seed-idgen ids, the purchase entity). The port gains `credit` / `reparent` (+ `LedgerPort.Credited`), defaults refused
in words; `PerRootLedger` delegates; `MemLedgerPort` answers over `Credit`. `PrepaidAdminTest` (5). **Reds:** expired accounts hidden → the
accounts test; purchase answering null → its test; a credit that never reaches the ledger → the credit and the refusal tests.

## 4 · The suite, per module

| Module | cb0e840 | 623e12a (1) | 368a6c3 (2a) | 222807a (2b) | 8bcbd8e (3) | e6dff43 (4) | 8f0974e (5) |
|---|---|---|---|---|---|---|---|
| seed-sessionflow | 294 | 294 | 167 (−131 moved, +4) | 167 | 167 | 167 | 167 |
| seed-switch-ledger | 70 | 70 | 52 (−18 moved) | 52 | 52 | 52 | 52 |
| seed-prepaid | — | 21 (6 skip) | 171 (+131 +18 +1) | 199 | 224 | 230 | 235 |
| seed-sessionflow-samples | 76 | 76 | 76 | 76 | 76 | 78 | 78 |
| the other 10 modules | 258 (3 skip) | 258 | 258 | 258 | 258 | 258 | 258 |
| **total** | **698/0/0/3** | **719/0/0/9** | **724/0/0/9** | **752/0/0/9** | **777/0/0/9** | **785/0/0/9** | **790/0/0/9** |

The parity rows at the head: 21 on the base lane (15 pass, 6 `@Disabled` gaps) + 21 on the subclass Prepaid lane + 21 on the builder
Prepaid lane (42/0/0/0) + the SMS-shaped 6. Logs: `~/l2a-work/{before-2a-623e12a,2a-final,2b-final,3-final,4-final,5-final}.log`, per
class `~/l2a-work/classes-*.txt`.

## 5 · The base's surface after L2a (what ad-sphere and the next rounds see)

- **New:** `spi/MoneyEngine`, `spi/MoneyEngineMaker`, `spi/MoneyRules`, `api/LaneAnswers`; `RoutesphereFlow.money()` / `answers()` /
  `moneyRules()`; `RoutesphereKit.moneyMaker()` / `Builder.money(...)`; `RoutesphereContext.walked(...)`; `RoutesphereApp.Builder.prepaid(...)`;
  `LedgerPort.balanceOf` / `charge` / `refund` / `credit` / `reparent` + `LedgerPort.Credited` (defaults: empty or refused in words).
- **Unchanged signatures:** every protected hook (`reserveBalance`, `finalizeTier`, `renewWindowSeconds`, `reserveWindow`, `chargeAtSettle`,
  `rateNextWindow`, `nextAccount`, …), `renewReserves`, `simulate`, `handOver`, `publishWhatWasLeftInTheAir`; every type ad-sphere imports
  (`LedgerPort`, `LedgerMaker`, `LedgerSettings`, `Ledgers`, `TierRate`, `RefundPolicy`, `TierSettlement`, `SettleOutcome`, `AccountOrder`).
- **The start:** an engine on a classpath without seed-prepaid is refused in words.

## 6 · What L2b must do

1. **The one-batch write** over mem-ledger's `execCrud(WALEntryBatch)` (round L1): swap `Books`' per-row writes (every write of the switch
   ledger is in `Books`: the reserve row open / grow / delete, the account's move, the debit). Then change the ORDER: rate and authorize
   every tier first, then ONE batch for the hold of every tier (P5 becomes "nothing to return"; `PrepaidEngine.release` and
   `TierReserve.release` lose their saga), the renewal of every tier in one batch, the settlement of every tier in one batch. The parity
   rows pin the money outcomes; they stay green.
2. **Durable references:** a reference row written IN the same unit (today the idempotency memories — `MemLedgerPort.answered`,
   `ClosedTiers`, `Charges` — are in memory and bounded). A CHARGE_NOW charge is not journaled in L2a (final by design): in L2b its unit
   carries its reference row, so a restart cannot charge twice nor lose the refund's claim.
3. **LedgerDb** (design §9): tables by name prefix, every tier of the tree, the targeted reload from the doorbell's `changes` (S1–S7); the
   catalog in the cache (S4: `AccountWalk` reads the accounts through `AccountOrder` from the TREE today; `admin().accounts` too).
4. **Purchase** through the ledger with ids from seed-idgen (`admin().purchase` refuses today), and the money roads mounted by the apps.
5. **One engine per root** (design §10): today a Prepaid serves its kit; a kit from the bootstrap routes each tier to its root's ledger
   (`PerRootLedger`), which is enough for L2a's verbs.
6. **Owner's word needed:** the SMS port — the live charges only the LAST tier of the chain (D10); the seed's CHARGE_NOW charges every
   tier. Port-as-is says the live's; the design says every tier.
7. Smaller: the window cadence is the kit's (`RoutesphereSettings`), the Prepaid must say the same (refused otherwise) — L2b may make the
   Prepaid the budget child's source; a `ratePrefix` rule for the CDR (D6); the end's re-looked-up rate on the level for the CDR (D5);
   standalone `Prepaid` verbs for a pipeline outside `RoutesphereFlow` (today the engine reads a flow's `LaneAnswers`).

## 7 · What I could not do, and why

- **ad-sphere's suite** was not run: it is the architect's gate (one dependency line, §0). The seed-facing samples are green.
- **The orchestrix ledger** gets none of the five new port verbs (defaults: no peek → whole windows only; charge / refund / credit /
  reparent refused in words): its roads are the BSS's; a CHARGE_NOW lane on it would be refused at its first charge.
- **The testkit's `InMemoryLedger`** implements none of them either (the samples' Prepaid test is a dry run; the parity rows run on the
  real switch ledger).
- **Class size:** every class of seed-prepaid is under 300 lines (the largest, `TierReserve`, 290); `AdmissionChain` went from 356 to 190;
  `RoutesphereFlow` stays over the rule as it was at cb0e840 (886 → 889 lines: the engine's two doors and the `moneyRules` hook in, the
  money bodies out) — splitting it is not this round's.
- Nothing pushed (the rule). No sudo, no systemctl, nothing listening (H2 in process), nothing left running.
