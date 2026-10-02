package com.telcobright.seed.callflow;

import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.api.CallFlowEngine;
import com.telcobright.seed.callflow.api.CallFlowTimings;
import com.telcobright.seed.callflow.api.CallState;
import com.telcobright.seed.callflow.dependencies.CallFlowSettings;
import com.telcobright.seed.callflow.samples.AdFlow;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.samples.VoiceFlow;
import com.telcobright.seed.callflow.samples.Wire;
import com.telcobright.seed.callflow.testkit.ManualClock;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.session.AdmissionVerdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The admission budget: admission has ONE deadline, and everything inside it fits. The candidates that pay share the
 * ADMITTING deadline minus a reserve; every ledger call gets only what is left; when nothing is left no candidate that
 * pays is started and the ledger is not asked; a free candidate (the house ad) still plays in the reserve.
 *
 * <p>The scene's deadlines: ADMITTING 2 s, the reserve 500 ms, so the candidates that pay have 1,500 ms. The tests with no
 * machine move the clock by hand (a slow ledger "takes" time by advancing it): they are exact and wait for nothing.
 */
class CallFlowBudgetTest {

    private static final long BUDGET_MS = 1500;

    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-03T04:00:00Z"), Scene.DHAKA);
    private final Scene scene = new Scene().withClock(clock);
    private final VoiceFlow voice = scene.voice(Scene.settings(4));
    private final AdFlow ad = scene.ad(Scene.settings(4), true);
    private final List<CallFlowEngine<?>> engines = new ArrayList<>();

    CallFlowBudgetTest() { scene.ledger.timePassesBy(clock::advance); }

    @AfterEach
    void stopEngines() { engines.forEach(CallFlowEngine::close); }

    private AdmissionVerdict admit(VoiceFlow.Call call) {
        assertThat(voice.preprocess(call)).isNull();
        return voice.admit(call, StepMode.LIVE);
    }

    private AdmissionVerdict admit(AdFlow.View view) {
        assertThat(ad.preprocess(view)).isNull();
        return ad.admit(view, StepMode.LIVE);
    }

    private long elapsedSince(Instant start) { return clock.millis() - start.toEpochMilli(); }

    // ── every ledger call is inside the budget ──────────────────────────────

    @Test
    void theSettingsSayTheBudget_theAdmittingDeadlineMinusTheReserve() {
        CallFlowSettings settings = Scene.settings(4);

        assertThat(settings.admissionBudgetMs()).isEqualTo(BUDGET_MS);
        assertThat(settings.withAdmissionReserveMs(200).admissionBudgetMs()).isEqualTo(1800);
        assertThatThrownBy(() -> settings.withAdmissionReserveMs(2000))
            .as("a reserve as long as the deadline leaves the paying candidates no time").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.withAdmissionReserveMs(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyTierIsGivenOnlyWhatIsLeftOfTheBudget() {
        scene.ledger.slowOn("res_44", 701, 400);                                    // the leaf tier's reserve takes 400 ms
        Instant start = clock.instant();

        AdmissionVerdict verdict = admit(Scene.call("b-1", "10.0.0.7", "01712345678"));

        assertThat(verdict.accepted()).isTrue();
        assertThat(scene.ledger.timeGivenFor("b-1#L0")).as("the leaf tier gets the whole budget").isEqualTo(BUDGET_MS);
        assertThat(scene.ledger.timeGivenFor("b-1#L1")).as("the tier above gets what the leaf left").isEqualTo(BUDGET_MS - 400);
        assertThat(elapsedSince(start)).isEqualTo(400);
    }

    @Test
    void aLedgerThatDoesNotAnswerInTheBudget_isAFault_andTheHouseAdStillPlaysInTheReserve() {
        scene.ledger.slowOn("res_44", 701, 5000);                                   // the ledger hangs for the paying advertiser
        AdFlow.View view = Scene.view("b-2", "dhaka-zone");                         // campaign 10 (702, no money), campaign 11 (701), the house ad
        Instant start = clock.instant();

        AdmissionVerdict verdict = admit(view);

        assertThat(verdict.accepted()).isTrue();
        assertThat(view.playing.houseAd()).as("the house ad plays").isTrue();
        assertThat(view.systemFault).isEqualTo(CallCause.BILLING_SYSTEM_ERROR);
        assertThat(elapsedSince(start)).as("the hanging ledger was given the budget and not a millisecond more").isEqualTo(BUDGET_MS);
        assertThat(scene.ledger.count("reserve")).as("no money moved").isZero();
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void whenTheBudgetIsSpent_noCandidateThatPaysIsStarted_andTheCauseIsAdmissionTimeout() {
        scene.ledger.slowOn("res_44", 702, BUDGET_MS);                              // "cannot pay", answered at the budget's very end
        AdFlow.View view = Scene.view("b-3", "paying-zone");                        // campaign 10 (702, no money), campaign 11 (701): no house ad

        AdmissionVerdict verdict = admit(view);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).isEqualTo(CallCause.ADMISSION_TIMEOUT);
        assertThat(view.budgetSpent).isTrue();
        assertThat(view.candidatesTried).as("campaign 11 was not even started: no partner check, no channel slot").isEqualTo(1);
        assertThat(scene.ledger.timesAsked("reserve")).as("campaign 11 could pay, but it was never asked: no time").isEqualTo(1);
        assertThat(scene.ledger.timeGivenFor("b-3#2#L0")).isNull();
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
    }

    @Test
    void aBudgetThatIsNotSpent_changesNothing_theNextCampaignPays() {
        scene.ledger.slowOn("res_44", 702, BUDGET_MS - 1);                          // one millisecond is left
        AdFlow.View view = Scene.view("b-4", "paying-zone");

        AdmissionVerdict verdict = admit(view);

        assertThat(verdict.accepted()).isTrue();
        assertThat(view.playing.id()).isEqualTo(11);
        assertThat(view.budgetSpent).isFalse();
        assertThat(scene.ledger.timeGivenFor("b-4#2#L0")).as("the second campaign's leaf gets the one millisecond left").isEqualTo(1);
    }

    @Test
    void aTierIsNotAskedWhenNoTimeIsLeft_andTheTierBelowGetsItsReserveBack() {
        scene.ledger.slowOn("res_44", 701, BUDGET_MS);                              // the leaf reserves, at the budget's very end
        VoiceFlow.Call call = Scene.call("b-5", "10.0.0.7", "01712345678");

        AdmissionVerdict verdict = admit(call);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).isEqualTo(CallCause.ADMISSION_TIMEOUT);
        assertThat(scene.ledger.timeGivenFor("b-5#L1")).as("the tier above was not asked").isNull();
        assertThat(scene.ledger.count("release")).as("the leaf's reserve went back").isEqualTo(1);
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.balanceOf("btcl", 44)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.openReserves()).isZero();
        assertThat(call.levels).isEmpty();
    }

    @Test
    void aDryRunHasNoBudget_itAsksNothingOfTheLedger() {
        scene.ledger.slowOn("res_44", 701, 5000);

        assertThat(voice.simulate(Scene.call("b-6", "10.0.0.7", "01712345678")).admitted()).isTrue();

        assertThat(scene.ledger.timesAsked("reserve")).isZero();
        assertThat(clock.millis()).as("no time passed").isEqualTo(Instant.parse("2026-10-03T04:00:00Z").toEpochMilli());
    }

    // ── on a real machine, in real time ─────────────────────────────────────

    /**
     * The whole point of the budget (item 6b of the ad story): with a ledger that hangs, the view is NOT ended by the
     * ADMITTING deadline. The paying campaign is given up inside the budget and the house ad is admitted before the
     * state's own 2 s are over.
     */
    @Test
    void onAMachine_aHangingLedgerDoesNotEndTheViewAtTheAdmittingDeadline_theHouseAdIsAdmitted() throws Exception {
        Scene live = new Scene();                                                   // the real clock, a ledger that really sleeps
        live.ledger.slowOn("res_44", 701, 6000);
        CallFlowSettings settings = new CallFlowSettings(4, 2, 60, new CallFlowTimings(2, 2, 4, 0, 20, 2), 0, 0, false);
        CallFlowEngine<AdFlow.View> engine = CallFlowEngine.of(live.ad(settings, true)).child(Wire.TYPE, Wire::new).start();
        engines.add(engine);
        AdFlow.View view = Scene.view("b-7", "dhaka-zone");
        long startedMs = System.currentTimeMillis();

        assertThat(engine.launch(view).launched()).isTrue();
        assertThat(engine.awaitSettled("b-7", 5, TimeUnit.SECONDS)).isTrue();
        long tookMs = System.currentTimeMillis() - startedMs;

        assertThat(engine.stateOf("b-7")).isEqualTo(CallState.ADMITTED);
        assertThat(view.playing.houseAd()).isTrue();
        assertThat(view.endCause).isNull();
        assertThat(tookMs).as("given up at the budget (1,500 ms), before the ADMITTING deadline (2,000 ms)").isBetween(1400L, 1990L);
        assertThat(live.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
    }
}
