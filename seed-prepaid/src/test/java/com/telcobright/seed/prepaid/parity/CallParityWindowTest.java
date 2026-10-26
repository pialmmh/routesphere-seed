package com.telcobright.seed.prepaid.parity;

import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.internal.BudgetChild;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.SessionTimings;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.telcobright.seed.prepaid.parity.ParityScene.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parity with the live statewalk-v2 call, the window rows (design §10.3 P7–P12): the renewal timer, the renewal on the call's own
 * machine, the whole unit or the remainder, the narrowest tier and the cut, the cut that never settles, the money's lifetime. The rows
 * with a timer run on the engine with a short cadence (1 s / 2 s); the money rows at the handler level with the manual clock.
 */
class CallParityWindowTest extends ParityRows {

    /**
     * P7 · statewalk-v2-call {@code CallSupervisor.startPeriodicReserve} 881–897: the timer starts at the answer (447) — the first tick after
     * {@code reserveInitialDelayMs} (58 s), then every {@code reserveIntervalMs} (60 s); a no-op when not configured (882). The seed: the
     * settings' {@code reserveInitialDelaySec} / {@code reservePeriodSec}; the budget child rests until the answer, FIRST_RENEWAL once, then
     * RENEWING; no child at all when the period is 0.
     */
    @Test
    void P7_theTimerStartsAtTheAnswer_first58s_thenEvery60s_offWhenNotConfigured() throws Exception {
        RoutesphereSettings live = new RoutesphereSettings(8, 2, 12_000, new SessionTimings(2, 2, 5, 10_800), 60, 58, 0, false, 200);
        assertThat(live.renews()).isTrue();
        assertThat(live.firstRenewalSec()).isEqualTo(58);
        assertThat(live.withReservePeriodSec(0).renews()).as("off when not configured").isFalse();

        scene = openWithEngine(1, 2, Map.of());
        scene.dial("p7", PACKAGES_ONLY, DOMESTIC);
        assertThat(scene.engine.registry().childStateOf("p7", BudgetChild.TYPE)).as("before the answer: resting").isEqualTo(BudgetChild.HOLDING);
        Thread.sleep(1500);
        assertThat(scene.ctx("p7").tiers.get(0).reservationCount).as("no renewal before the answer").isEqualTo(1);

        scene.answerOnTheWire("p7");
        assertThat(scene.engine.registry().childStateOf("p7", BudgetChild.TYPE)).isEqualTo(BudgetChild.FIRST_RENEWAL);
        scene.awaitRenewals("p7", 2, 3000);
        assertThat(scene.engine.registry().childStateOf("p7", BudgetChild.TYPE)).as("after the first tick: one per period").isEqualTo(BudgetChild.RENEWING);
        scene.awaitRenewals("p7", 3, 3000);
        scene.hangUpOnTheWire("p7");

        assertThat(scene.ctx("p7").tiers.get(0).reservationCount).isGreaterThanOrEqualTo(3);
        assertThat(scene.rows("res_2")).isZero();
    }

    /**
     * P8 · {@code CallSupervisor} 810–820: each tick PUBLISHES {@code ReserveNextMinute} onto the call's own BalanceTracker — "publishing
     * (rather than reserving on the timer thread) is what keeps the balance single-threaded per call: a renewal can never interleave with
     * the settlement of the same call". The seed: the tick is the budget child's own timeout on its cell, and every renewal and the
     * settlement of one session run under the session's money lock — never on the registry's timer thread.
     */
    @Test
    void P8_eachRenewalIsAnEventOnTheCallsOwnMachine_neverOnTheTimerThread() throws Exception {
        scene = openWithEngine(0, 1, Map.of());
        scene.dial("p8", PACKAGES_ONLY, DOMESTIC);
        scene.answerOnTheWire("p8");
        scene.awaitRenewals("p8", 3, 4000);
        scene.hangUpOnTheWire("p8");

        assertThat(scene.recorder.seen).filteredOn(s -> s.contains("#W")).as("every renewal under the session's money lock").isNotEmpty().allMatch(s -> s.contains("locked=true"));
        assertThat(scene.recorder.seen).filteredOn(s -> s.startsWith("settle")).isNotEmpty().allMatch(s -> s.contains("locked=true"));
        assertThat(scene.recorder.seen).filteredOn(s -> s.contains("#W")).noneMatch(s -> s.toLowerCase().contains("timer") || s.toLowerCase().contains("scheduler"));
        assertThat(scene.ctx("p8").outcome).isEqualTo(Outcome.SUCCEEDED);
    }

    /**
     * P9 · routesphere-core {@code BalanceBillingService.reserveNextWindowSeconds} 86–120: a whole unit first (91); refused, the REMAINDER is
     * held and converted to seconds (93–115: "0.64 min stranded in the sbc1 2026-08-04 test"), below 1 s nothing (101); a zero rate funds
     * the whole window and keeps the cadence (89).
     */
    @Test
    @Disabled("gap: RoutesphereApp has no renewWindowSeconds hook; the base's default renewal (AdmissionChain.renewThroughLedger 342–350) "
        + "answers 0 when the whole window is refused — the remainder lives only in seed-switch-ledger's WindowRenewal, reachable by a subclass")
    void P9_aWholeUnit_elseTheRemainderInSeconds_aZeroRateKeepsTheCadence() {
        scene = open(Map.of("res_2#41", "2.5"));
        CallContext call = scene.admitted("p9", PACKAGES_ONLY, DOMESTIC);                    // 1 held, 1.5 left
        scene.answer(call);

        assertThat(scene.renew(call)).as("a whole minute").isEqualTo(60.0);                  // 0.5 left
        assertThat(scene.renew(call)).as("the remainder: 0.5 min = 30 s").isEqualTo(30.0);     // 0 left
        assertThat(scene.renew(call)).as("nothing left").isZero();
        assertThat(scene.balance("res_2", 41)).isEqualByComparingTo("0");
        assertThat(scene.row("res_2", "p9#L0").getReserveUnit()).isEqualByComparingTo("2.5");
        assertThat(call.levels.get(0).getReservationCount()).isEqualTo(3);

        CallContext zeroRated = scene.admitted("p9-z", DIRECT, ZERO_RATED);                   // 88019 → 0.00
        scene.answer(zeroRated);
        assertThat(scene.renew(zeroRated)).as("zero-rated: nothing to fund, keep the cadence").isEqualTo(60.0);
        assertThat(scene.balance("btcl", 91)).isEqualByComparingTo("100");
    }

    /**
     * P10 · statewalk-v2-call {@code BalanceTracker.renewOrSignal} 224–250: the narrowest tier decides (228–236); a final partial window
     * is published and the cut scheduled for the moment the credit ends ({@code CallSupervisor.scheduleFinalCut} 907–925 — not at the next
     * tick); nothing left → the cut now (241–244). The cadence here is a first renewal at 1 s, then every 2 s (2b: with a 1 s period no
     * partial window can exist — a remainder is less than one unit, so less than 1 s, and the live floor {@code MIN_FINAL_WINDOW_SEC} = 1 s
     * of P9 makes it "nothing left"; the row first ran in 2b and its scene moved to a 2 s window, its rule unchanged).
     */
    @Test
    @Disabled("gap: a partial window cannot happen on the base through RoutesphereApp (P9's gap) — the cut comes at the next tick, not when "
        + "the credit ends; the narrowest tier and the cut-now halves pass")
    void P10_theNarrowestTierDecides_aPartialWindowCutsAtItsEnd_nothingLeftCutsNow() throws Exception {
        scene = openWithEngine(1, 2, Map.of("res_2#11", "2.5", "res_2#41", "2.0"));

        scene.dial("p10-partial", UNILEVER, DOMESTIC);                                     // leaf 1.5 left, root 9 left: the leaf is the narrowest
        scene.answerOnTheWire("p10-partial");
        long partialMs = scene.awaitEnd("p10-partial", 8000);                              // tick 1 s: whole (0.5 left); tick 3 s: 0.5 unit = 1 s of a 2 s window → the cut at ~4 s
        assertThat(scene.ctx("p10-partial").endCause).isEqualTo("BALANCE_EXHAUSTED");
        assertThat(partialMs).as("cut when the remainder ended, before the third tick (5 s)").isBetween(3700L, 4900L);
        assertThat(scene.ctx("p10-partial").tiers.get(1).reservationCount).as("the root held every tick too").isGreaterThanOrEqualTo(3);

        scene.dial("p10-now", PACKAGES_ONLY, DOMESTIC);                                    // 1 held, 1 left
        scene.answerOnTheWire("p10-now");
        long nowMs = scene.awaitEnd("p10-now", 8000);                                      // tick 1 s: whole (0 left); tick 3 s: nothing → the cut now
        assertThat(scene.ctx("p10-now").endCause).isEqualTo("BALANCE_EXHAUSTED");
        assertThat(nowMs).isBetween(2900L, 3950L);
        assertThat(scene.rows("res_2")).isZero();
    }

    /**
     * P11 · {@code CallSupervisor} 457–468: the balance ran out — the channel is killed but the supervisor STAYS, so the resulting hangup
     * drives the settlement on the true talk time (BalanceTracker 221–222: "deliberately does NOT settle here"). The seed: BudgetExhausted
     * → Stop down the wire → the final state settles on {@code durationSec}; the row lives until then.
     */
    @Test
    void P11_theCutDoesNotSettle_theHangupSettlesOnTheRealTalkTime() throws Exception {
        scene = openWithEngine(0, 1, Map.of("res_2#41", "2.0"));
        scene.dial("p11", PACKAGES_ONLY, DOMESTIC);
        scene.answerOnTheWire("p11");
        assertThat(scene.rows("res_2")).as("the row is open while the call talks").isEqualTo(1);
        scene.awaitEnd("p11", 6000);

        CallContext call = scene.ctx("p11");
        assertThat(call.endCause).isEqualTo("BALANCE_EXHAUSTED");
        assertThat(call.outcome).isEqualTo(Outcome.SUCCEEDED);
        assertThat(call.durationSec).as("the real talk time: about two ticks").isBetween(1.5, 3.5);
        assertThat(call.settlements.get(0).charged().doubleValue()).as("charged the talk time, not the holds").isBetween(0.02, 0.07);
        assertThat(call.settlements.get(0).returned()).isEqualByComparingTo(call.settlements.get(0).reserved().subtract(call.settlements.get(0).charged()));
        assertThat(scene.balance("res_2", 41)).isEqualByComparingTo(java.math.BigDecimal.valueOf(2.0).subtract(call.settlements.get(0).charged()));
        assertThat(scene.rows("res_2")).as("the row died with the settlement").isZero();
        assertThat(scene.recorder.seen).filteredOn(s -> s.startsWith("settle")).hasSize(2);
    }

    /**
     * P12 · {@code BalanceTracker.lifetimeSeconds} 77–90: the money state must outlast every deadline of the call added together, plus
     * 120 s of margin ("on sbc1 the tracker closed without settling 15 s before the 26 Sep 2026 Innoversal call was cut"). The seed: the
     * lifetime cap must exceed the sum of the state timers (refused in words otherwise), and a call as long as ACTIVE allows settles whole.
     */
    @Test
    void P12_theMoneyLivesAsLongAsEveryDeadlineAdded_plusTheMargin() {
        assertThatThrownBy(() -> new RoutesphereSettings(8, 2, 3600, new SessionTimings(5, 5, 60, 3600), 60, 58, 0, false, 200))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("longer than the longest healthy session");

        scene = open();
        CallContext call = scene.admitted("p12", PACKAGES_ONLY, DOMESTIC);
        scene.answer(call);
        scene.talk(7200);                                                                  // two hours, no renewal in between
        scene.hangUp(call);

        assertThat(call.durationSec).isEqualTo(7200.0);
        assertThat(call.settlements.get(0).closed()).isTrue();
        assertThat(call.settlements.get(0).charged()).isEqualByComparingTo("120");
        assertThat(scene.balance("res_2", 41)).as("the overrun was debited: 100 − 120").isEqualByComparingTo("-20");
        assertThat(scene.rows("res_2")).isZero();
    }
}
