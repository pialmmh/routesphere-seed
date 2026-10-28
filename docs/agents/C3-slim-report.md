# C3 part 1 — the slim prepaid library · report

**Round:** C3 of the owner's ruling of 2026-10-28 (design §14.1: the base prepaid lib = `reserveBalance` / `finalizeBalance`; SPI: units,
rate, actual charge, no-balance cause; config: charge now or windowed, window, first renewal, accounts, refund on failure). The owner,
06:0x: "go as you recommend best". Branches: routesphere-seed `c3-slim` (off c4-catalog 7cfbaf8), ad-sphere `c3-units` (off c4-money
7c1fc28). Nothing deployed.

## What the builder offers now

```java
Prepaid.<CallContext>builder("call")
    .admission(Admission.HOLD_WINDOW).window(w).firstRenewal(f)          // config: windowed (or CHARGE_NOW), its cadence
    .units(c -> intlOut(c) ? Set.of() : Set.of("TF_min"))                 // SPI: the package units that may pay THIS request; none = cash only
    .rate((c, tier, account) -> rates.unitRate(c, tier, account))         // SPI: what ONE unit costs on this account at this tier
    .noBalanceCause((c, tier) -> intlOut(c) ? NO_BALANCE_INT_OUT : INSUFFICIENT_BALANCE)   // SPI
    .actualCharge((c, tier) -> rates.cost(c, tier))                       // SPI: the windowed lane's actual charge at the end
    .accounts(AccountRule.UNITS_THEN_CASH)                                 // config
    .build(kit);
```

| Change | Why |
|---|---|
| `Admission.HOLD` gone (HOLD_WINDOW · CHARGE_NOW) | the ad view is charged now (the owner, 2026-10-27); no lane holds a whole price |
| `packageUnits` → `units`; `refusalCause` → `noBalanceCause` | §14.1's names |
| `chooseAccounts` gone | `units` says it: the call abroad = no units = cash only; the SMS = its message units — the same accounts, in the same order (`PackageMatch.payable`) |
| `beforeHold`, `onExhausted`, `onLowBalance` (+ the settings' `lowBalanceAt`), `onFinished`, `hooks` (spi `PrepaidHooks`, `LaneHooks`, `LowBalanceMark`) gone | no lane used them; a refused renewal already ends the session through the base; a notice or a record is the lane's own business on its flow |
| `PrepaidSettings.perEvent(admission)` → `chargedNow()` | one per-event admission left |
| ad-sphere `AdPrepaid` | `.units(...)` — its only change |

## Kept on purpose: `finalize`

§14.1 names "refund on failure" as config. The SMS lane's failure is "not DELIVERED" (`endCause`), which the base's `answered()` (an SMS
*submitted*) does not show. A boolean `refundOnFailure` needs a generic "failed" the engine can read; until the SMS port defines it,
`finalize(ctx -> Final)` stays (and `actualCharge` for a windowed lane). **C3 part 2** (with the SMS port): the engine's failure signal,
then `refundOnFailure(boolean)` replaces `finalize`; and `PrepaidAdmin`'s purchase onto the CRUD (its offer's package checked by the
ledger — C4-c).

## Mutants

| mutant | what it breaks | red |
|---|---|---|
| `c3-units-ignored` | the walk takes the default units, not the lane's — the call abroad pays from packages, the ad from the wrong unit | 10 failures (the call's admission parity rows, the ad lane, the SMS) |
| `c3-no-balance-cause-ignored` | the lane's no-balance cause dropped — the call abroad refused `INSUFFICIENT_BALANCE`, not `NO_BALANCE_INT_OUT` | 2 failures (P3 on both call lanes) |

Both red by failing tests (no compile error), restored by hash.

## Gates

| repo · commit | what | result |
|---|---|---|
| seed `c3-slim` c74b997 (C2 + C2b + C4 + C3 part 1 + the rights check) | clean archive: the suite, the PostgreSQL lab in; installed | 900/0/0/9 (C3 changed no count; +1: TierRightsLabTest) |
| ad-sphere `c3-units` 884f01c on seed c74b997 | clean archive: the suite + the switch-ledger PostgreSQL ITs | 1007/0/0/7 + AdSwitchLedgerPostgresIT 3/0, AdSwitchMoneyPostgresIT 2/0, AdTierCreatedPostgresIT 4/0/1 |
