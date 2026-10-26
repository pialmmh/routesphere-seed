package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.DryRun;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.statewalk.session.Verdict;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.telcobright.seed.prepaid.parity.ParityScene.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity with the live statewalk-v2 call, the admission rows (design §10.3 P1–P6, P15, P21): one test per row, named after it, the money
 * asserted on the REAL switch ledger — balances, the hold row, the cause. The lane is the call-shaped {@link CallLane}: no money move of
 * its own. A row the lane cannot express on today's base is written as it should be and {@code @Disabled("gap: …")}.
 */
class CallParityAdmissionTest extends ParityRows {

    /**
     * P1 · routesphere-core {@code pipeline/request/call/rules/ReserveBalanceStep} 62–135: at admission ONE unit is held per tier — a
     * package minute ({@code TF_min}) = 1.0 (88–89); cash ({@code BDT}) = the rate of a minute (90–93); international = the root's 117
     * rate (85–87) — the reserve row opened (the switch's {@code PrepaidServiceWithCompensation.reserveBalance} 96–119) and the account
     * debited (121–136).
     */
    @Test
    void P1_atAdmission_oneUnitIsHeldPerTier_packageMinute_cashRate_intlRootRate() {
        scene = open();

        scene.admitted("p1-a", UNILEVER, DOMESTIC);                                   // TF_min at both tiers
        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("1.5");
        assertThat(scene.row("res_2", "p1-a#L0").getReserveUnit()).isEqualByComparingTo("1.0");
        assertThat(scene.row("res_2", "p1-a#L0").getUom()).isEqualTo("TF_min");
        assertThat(scene.balance("btcl", 21)).as("the reseller's own package minute at the root").isEqualByComparingTo("9");
        assertThat(scene.row("btcl", "p1-a#L1").getReserveUnit()).isEqualByComparingTo("1.0");

        scene.admitted("p1-b", DIRECT, DOMESTIC);                                     // cash: the rate of a minute (8801 → 0.75)
        assertThat(scene.balance("btcl", 91)).isEqualByComparingTo("99.25");
        assertThat(scene.row("btcl", "p1-b#L0").getReserveUnit()).isEqualByComparingTo("0.75");

        scene.admitted("p1-c", DIRECT, INTL_UK);                                      // abroad: the root's 117 rate (44 → 1.50)
        assertThat(scene.balance("btcl", 91)).isEqualByComparingTo("97.75");
        assertThat(scene.row("btcl", "p1-c#L0").getReserveUnit()).isEqualByComparingTo("1.50");
    }

    /**
     * P2 · {@code ReserveBalanceStep} 159–219: only the accounts unexpired at call time pay ({@code unexpired} 187–207: the expired
     * package 13 is skipped); packages before cash ({@code orderedAccounts} 159–185); each account is TRIED until one pays (82–123: "an
     * exhausted package falls through to the customer's cash instead of rejecting the call outright" — partner 349, sbc1, 2026-08-05).
     */
    @Test
    @Disabled("gap: the base holds on the ONE account rateAtLevel chose (AdmissionChain.rateAndReserve 171–188) — a refused account is "
        + "INSUFFICIENT_BALANCE, the next is never tried; the live ReserveBalanceStep 82–123 walks every fundable account until one pays")
    void P2_unexpiredOnly_packagesBeforeCash_eachAccountTriedUntilOnePays() {
        scene = open(Map.of("res_2#11", "0.5"));                           // Unilever's package has half a minute left

        scene.admitted("p2", UNILEVER, DOMESTIC);

        assertThat(scene.balance("res_2", 11)).as("the package could not fund a minute: untouched").isEqualByComparingTo("0.5");
        assertThat(scene.balance("res_2", 13)).as("the expired package is never asked").isEqualByComparingTo("100");
        assertThat(scene.balance("res_2", 12)).as("the cash paid the minute at 0.60 (88017)").isEqualByComparingTo("99.40");
        assertThat(scene.row("res_2", "p2#L0").getIdPackageAccount()).isEqualTo(12L);
        assertThat(scene.rows("res_2")).isEqualTo(1);
    }

    /**
     * P3 · {@code ReserveBalanceStep} 68–80: international outgoing is cash-only ({@code bdtAccounts} 209–219 — a package account cannot
     * place it); {@code DefaultAdmissionService} 284–286: a package-only partner and a cash shortfall alike are {@code NO_BALANCE_INT_OUT},
     * never {@code INSUFFICIENT_BALANCE}; nothing is held.
     */
    @Test
    @Disabled("gap: RoutesphereApp has no refusalCause / noBalanceCause hook — a cash account short of the intl minute is refused "
        + "INSUFFICIENT_BALANCE by the base (RoutesphereFlow.noBalanceCause 424), the live says NO_BALANCE_INT_OUT (DefaultAdmissionService 284–286)")
    void P3_intlOutgoingIsCashOnly_causeNO_BALANCE_INT_OUT() {
        scene = open();

        Verdict packagesOnly = scene.admit(scene.call("p3-a", PACKAGES_ONLY, INTL_UK));
        Verdict cashShort = scene.admit(scene.call("p3-b", CASH_ONLY, INTL_UK));       // 1.00 of cash against a 1.50 minute

        assertThat(packagesOnly.cause()).isEqualTo(CallLane.NO_BALANCE_INT_OUT);
        assertThat(cashShort.cause()).isEqualTo(CallLane.NO_BALANCE_INT_OUT);
        assertThat(scene.balance("res_2", 41)).isEqualByComparingTo("100");
        assertThat(scene.balance("res_2", 31)).isEqualByComparingTo("1.00");
        assertThat(scene.rows("res_2")).isZero();
        assertThat(scene.rows("btcl")).isZero();
    }

    /**
     * P4 · {@code ReserveBalanceStep} 103–121: the hold IS the balance check (no separate read); a THROWN error is the billing system's,
     * {@code BILLING_SYSTEM_ERROR}, never a balance cause ({@code DefaultAdmissionService} 277–283: "during the sbc1 2026-08-13 WAL outage
     * every call was mislabeled INSUFFICIENT_BALANCE").
     */
    @Test
    void P4_theHoldIsTheBalanceCheck_aThrownErrorIsBILLING_SYSTEM_ERROR_neverNoBalance() {
        scene = open();
        scene.lab.ledger().shutdown();                                                // the ledger does not answer

        CallContext call = scene.call("p4", UNILEVER, DOMESTIC);
        Verdict verdict = scene.admit(call);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.cause()).isEqualTo(SessionCause.BILLING_SYSTEM_ERROR);
        assertThat(call.systemFault).isEqualTo(SessionCause.BILLING_SYSTEM_ERROR);
        assertThat(call.levels).isEmpty();
    }

    /**
     * P5 · {@code DefaultAdmissionService.compensateReserves} 632–668: a later tier refuses (the root's digit filter, 270) after the leaf
     * held — the earlier holds are returned in full ({@code releaseReserves} 647–668), no row is left anywhere. L2b (design §10.3 P5:
     * "every tier rated and authorized first, then ONE batch — nothing to return"): the same money; the ledger is never asked, no record.
     */
    @Test
    void P5_aLaterTierRefuses_theEarlierHoldsAreReturned() {
        scene = open();
        long records = scene.lab.walRecords();

        Verdict verdict = scene.admit(scene.call("p5", UNILEVER, DENIED));

        assertThat(verdict.cause()).isEqualTo(CallLane.DIGIT_FILTER_DENIED);
        assertThat(scene.balance("res_2", 11)).as("the leaf's minute came back").isEqualByComparingTo("2.5");
        assertThat(scene.balance("btcl", 21)).isEqualByComparingTo("10");
        assertThat(scene.rows("res_2")).isZero();
        assertThat(scene.rows("btcl")).isZero();
        assertThat(scene.recorder.seen).as("L2b: nothing was held, nothing to give back — the ledger was never asked").isEmpty();
        assertThat(scene.lab.walRecords()).as("L2b: no record").isEqualTo(records);
    }

    /**
     * P6 · statewalk-v2-call {@code supervisor/BalanceTracker.callId} 162–173: the hold row is keyed by the CALL id, so the periodic
     * reserves add onto the admission reserve's row (14,368 orphan rows on BTCL by 2026-09-28 when they did not);
     * {@code PrepaidServiceWithCompensation.reserveBalance} 104–131: a CONSECUTIVE RESERVE grows the row's {@code reserveUnit} and refreshes
     * its time. The seed keys the row by the tier's reference {@code <call>#L<tier>}; its windows {@code #W<n>} grow that one row.
     */
    @Test
    void P6_theHoldRowIsKeyedByTheCall_renewalsGrowItAndRefreshItsTime() {
        scene = open();
        CallContext call = scene.admitted("p6", PACKAGES_ONLY, DOMESTIC);
        scene.answer(call);
        String openedAt = scene.row("res_2", "p6#L0").getTime();

        scene.talk(58);
        assertThat(scene.renew(call)).isEqualTo(60.0);
        scene.talk(60);
        assertThat(scene.renew(call)).isEqualTo(60.0);

        LevelAdmission leaf = call.levels.get(0);
        assertThat(scene.rows("res_2")).as("one row per tier per call").isEqualTo(1);
        assertThat(scene.row("res_2", "p6#L0").getReserveUnit()).isEqualByComparingTo("3.0");
        assertThat(scene.row("res_2", "p6#L0").getTime()).isEqualTo(scene.now().toString()).isNotEqualTo(openedAt);
        assertThat(leaf.getReservationCount()).isEqualTo(3);
        assertThat(leaf.getTotalReserved()).isEqualByComparingTo("3.0");
        assertThat(scene.balance("res_2", 41)).isEqualByComparingTo("97");
        assertThat(scene.recorder.seen).anyMatch(s -> s.startsWith("reserve p6#L0#W2 ")).anyMatch(s -> s.startsWith("reserve p6#L0#W3 "));
    }

    /**
     * P15 · {@code BalanceBillingService} 34 and {@code ReserveBalanceStep} 51: the rate is 0.40 when no prefix of the partner's plan
     * matches the number (227–254: "fall through to default"), at the hold and at the settlement alike (242–269).
     */
    @Test
    void P15_theDefaultRateIs040_whenNoPrefixMatches() {
        scene = open();

        CallContext call = scene.admitted("p15", DIRECT, UNRATED_PREFIX);
        assertThat(scene.row("btcl", "p15#L0").getReserveUnit()).isEqualByComparingTo("0.40");
        assertThat(call.levels.get(0).getRate()).isEqualByComparingTo("0.40");

        scene.answer(call);
        scene.talk(60);
        scene.hangUp(call);
        assertThat(call.settlements.get(0).charged()).isEqualByComparingTo("0.40");
        assertThat(scene.balance("btcl", 91)).isEqualByComparingTo("99.60");
    }

    /**
     * P21 · {@code ProcessingStep.StepMode.SIMULATE} and {@code ReserveBalanceStep.doSimulate} 138–148: the officer's Simulate walks the
     * admission with a mock level and moves no money — the seed's dry run: the tiers rated, nothing held, no row, the balances untouched.
     */
    @Test
    void P21_theOfficersSimulateMovesNoMoney() {
        scene = open();

        DryRun run = scene.flow.simulate(scene.call("p21", UNILEVER, DOMESTIC));

        assertThat(run.admitted()).isTrue();
        assertThat(run.levels()).hasSize(2);
        assertThat(run.levels().get(0).getRate()).isEqualByComparingTo("1.0");
        assertThat(run.levels().get(0).getPackageAccountId()).isEqualTo(11L);
        assertThat(scene.balance("res_2", 11)).isEqualByComparingTo("2.5");
        assertThat(scene.balance("btcl", 21)).isEqualByComparingTo("10");
        assertThat(scene.rows("res_2")).isZero();
        assertThat(scene.rows("btcl")).isZero();
        assertThat(scene.recorder.seen).as("the ledger was never asked").isEmpty();
    }
}
