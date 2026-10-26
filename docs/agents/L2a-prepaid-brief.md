# L2a — `seed-prepaid`: the call's money as a seed feature, parity-first (the Prepaid builder over today's engine)

**To:** the seed agent (worktree `routesphere-seed/worktrees/prepaid`, branch `prepaid` off `routesphere-flow` **cb0e840**) ·
**From:** the architect · **Date:** 2026-10-26 20:3x +0600
**Why:** the owner (2026-10-26): "create a package account based reusable prepaid accounting service, based on the call example live in
production today for esl calls … the prepaid logic shall be in the routesphere seed library, based on mem-ledger cache … for ad, sms, and
other applications, shall use no code or very little code to adapt." · "make sure it keeps identical logic for call … the feature parity
with existing statewalk 2 based design is important to validate that our design works."

**Read first, in this order:** routesphere `docs/architecture/prepaid-engine-design.md` (main 1adda6d9) — §4, §5, §10 (the builder, the
four lanes, the 58-s renewal, **the parity table P1–P21 with the live files and lines**), and `routesphere-seed-flow-requirements.md` R0
(the seed has no config file and is not runnable; the application feeds every value). Then the seed's money code as it is: `seed-sessionflow`
(`spi/LedgerPort`, `api/SessionSettlement`, `internal/BudgetChild`, `internal/ReturnRoad`, `dependencies/RefundPolicy`, `api/TierSettlement`,
`api/SessionCause`, `api/DryRun`, `api/RoutesphereApp`, `api/AppFlow`, `api/RoutesphereFlow`) and `seed-switch-ledger` (`api/*`,
`internal/MemLedgerPort`, `internal/Books`, `internal/AccountLocks`, `internal/OrphanReaper`, the tests and `LedgerLab`). The live call it
must match: routesphere-core `callmachine/v2impl/BalanceBillingService.java`, `pipeline/request/call/rules/ReserveBalanceStep.java`,
`callmachine/v2impl/DefaultAdmissionService.java` (632–668), `rtc/service/PrepaidServiceWithCompensation.java`; statewalk-v2-call
`supervisor/BalanceTracker.java`, `supervisor/CallSupervisor.java` (805–925), `billing/BillingPort.java`. Read them; never change them.

## 1 · The items (one commit each; every rule broken once and seen red; the suite's numbers before and after in the report)

| # | item | what | the proof |
|---|---|---|---|
| 0 | **The map.** One page in the report: every money step the seed has today → which live call line it ports → which Prepaid verb it becomes (admit · renewWindow · finish · admin). Name every place the seed differs from the live call. No code. | — | — |
| 1 | **The parity suite FIRST, on the base** (`seed-prepaid/src/test/java/…/parity/`): a call-shaped lane built with `RoutesphereApp` as the live call is (windowed, 60 s / 58 s, units `TF_min` and `BDT`, international cash-only with the root's rate, the 117-style re-rate at settlement, default rate 0.40) on the REAL ledger (`LedgerLab`: a MemLedger over H2, nothing listening) with a manual clock. **One test per row P1–P21**, named after the row, its javadoc citing the live file + lines it mirrors, asserting the money (balances, the hold row, the seconds funded, the cause, the CDR figures) — not the code shape. A row today's seed cannot express → the test written as it should be and `@Disabled("gap: …")`, and listed in the report with what is missing. Commit this BEFORE item 2: it is the regression net for every later round. | the suite runs on cb0e840 + the suite alone; the enabled tests green; the disabled ones listed |
| 2 | **`seed-prepaid`** (new module, the seed's package shape `api · spi · dependencies · internal · testkit`): `api/Prepaid` with `Prepaid.<C>builder(name)` exactly as design §10 (admission HOLD_WINDOW / HOLD / CHARGE_NOW; window + firstRenewal; rate; finalize → `api/Final` = charge(tier→cost) / releaseAll / refundAll / nothing; accounts rule UNITS_THEN_CASH / UNITS_ONLY / CASH_ONLY; chooseAccounts; refusalCause; beforeHold; onExhausted; onLowBalance; onFinished; reference; `build(kit)` refusing a missing required part in words); `spi/PrepaidHooks` (the optional callbacks, every one with a default); `dependencies/PrepaidSettings` (values only — R0); `internal/` = **today's engine moved, not rewritten**: the per-tier reserve, the window renewal with the remainder (`WindowRenewal`), the settlement in both directions, the release, the refund rule, the orphan reaper, the dry run — the same money moves as cb0e840 (item 1 proves it). Dependency direction: `seed-prepaid` depends on `seed-switch-ledger` (+ the domain), `seed-sessionflow` depends on `seed-prepaid`; if that makes a cycle, stop and propose the cut in the report before coding it. | the parity suite green on the new module with the SAME assertions; every existing seed test green |
| 3 | **ONE engine.** `RoutesphereApp.Builder.prepaid(prepaid)`; a lane that does NOT give one keeps working unchanged: the base builds its Prepaid from the lane's existing answers (`rateAtLevel`, `refundPolicy`, `settleOutcome`, `billedUnits`, `rateNextWindow`) so ad-sphere `p2-adflow` (ea1512f) needs no change to build on this branch. The flow calls `admit` at admission, `renewWindow` from the budget child, `finish` at the end — no second copy of any money step remains in `seed-sessionflow` (grep in the report). | ad-sphere's seed-facing tests: `seed-sessionflow-samples` green; the architect runs ad-sphere's suite on your branch |
| 4 | **CHARGE_NOW** — the SMS's final debit: every tier charged at admission in the same step, no hold row (`Books` gains the debit, cite `PrepaidServiceWithCompensation.debit` 440–461 and its reasons); `finish` with `Final.nothing` leaves it, `Final.refundAll` gives it back on every tier. An SMS-shaped parity test: delivered → nothing moves; failed + refund → back; failed + charge → nothing. | the tests; the mutants (a debit that leaves a hold row; a refund that skips a tier) red |
| 5 | **`prepaid.admin()`**: `credit(tier, accountId, amount, reference)` over the existing `Credit` (with `reparent`), `accounts(tier, partnerId)` as the tree names them today (S4 stays: the catalog is the tree's until L2b). `purchase` is NOT this round (needs ids + the purchase entity: L2b) — the method exists and refuses in words naming L2b. | tests |
| 6 | **Report** `docs/agents/L2a-prepaid-report.md`: the map (item 0), the gap list (item 1's disabled tests, each with the live line and what the seed lacks), per item the files and the reds pasted, the suite before (cb0e840) and after, what L2b must do (the one-batch write over mem-ledger's `execCrud(WALEntryBatch)` — coming from round L1 —, LedgerDb, purchase, the catalog in the cache). | — |

**Not this round:** the one-batch atomic write (L2b, after mem-ledger L1 lands — keep every write going through `Books` so L2b swaps ONE
place); the generic LedgerDb and the Kafka-driven targeted reload (L2b); `purchase` (L2b); any change under routesphere-core or
statewalk-v2-call (port as-is: the live call is read, never touched); ad-sphere (L3).

## 2 · Rules that stand

Java 21. Maven with YOUR private repository (`bash ~/arch-drafts/tools/private-m2.sh ~/l2a-work/m2`, then `-Dmaven.repo.local=~/l2a-work/m2`),
offline and under `flock /home/mustafa/telcobright-projects/.one-maven.lock` — never install into `~/.m2/repository`. statewalk 4.1.0 only
(the resolved version, proven by the tree — `feedback-prove-the-resolved-version`). `date` before any time you write. The commit trailer
`Co-Authored-By: <the model you run as> <noreply@anthropic.com>`. **No push.** EVERY commit waits for its test run's exit status. Nothing
listens on a port (H2 in process, as `LedgerLab`); scratch under `~/l2a-work`, never /tmp. No sudo, no systemctl, no `pkill -f`. No password
or secret anywhere. The seed has NO config file, no main, no runnable jar (R0): every value is a parameter. Top-level methods read as named
business steps (≤ ~10 lines; loops one layer down); every class ≤ ~300 lines; the house package shape. The samples module
(`seed-sessionflow-samples`) gets the call-shaped and SMS-shaped builders of design §10.1 as samples. A State line per item in
`~/l2a-work/STATE.md` (a stop must lose nothing). Nothing left running. The merge is mine.
