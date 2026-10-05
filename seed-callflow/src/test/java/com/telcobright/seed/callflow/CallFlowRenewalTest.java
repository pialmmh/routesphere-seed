package com.telcobright.seed.callflow;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.api.CallFlowEngine;
import com.telcobright.seed.callflow.api.CallState;
import com.telcobright.seed.callflow.api.CdrEvent;
import com.telcobright.seed.callflow.api.TierRate;
import com.telcobright.seed.callflow.dependencies.CallFlowSettings;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.samples.VoiceFlow;
import com.telcobright.seed.callflow.samples.Wire;
import com.telcobright.seed.callflow.testkit.InMemoryLedger;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.pipeline.StepMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The renewal of a long call (C14) in seconds: every tier answers how long it can still fund, the narrowest decides. A whole window
 * keeps the cadence; nothing left cuts the call at the tick; a partial window arms the cut for the moment the money ends — before
 * the next tick — so the last partial unit is spent. A renewal that throws never cuts a call. The default renewal holds the next
 * window through the ledger.
 */
class CallFlowRenewalTest {

    private final Scene scene = new Scene();
    private final List<CallFlowEngine<?>> engines = new ArrayList<>();

    @AfterEach
    void stopEngines() { engines.forEach(CallFlowEngine::close); }

    /** The sample voice flow with a scripted renewal at the leaf tier: one answer per tick (a number of seconds, or a throw). */
    private VoiceFlow scriptedVoice(CallFlowSettings settings, Deque<Object> leafAnswers) {
        return new VoiceFlow(scene.kit(settings), Map.of("10.0.0.7", 701),
            Map.of("res_44#701", new BigDecimal("0.60"), "btcl#44", new BigDecimal("0.40")), List.of(new VoiceFlow.Route("017", "GP-trunk", 5))) {
            @Override
            protected double renewWindowSeconds(Call call, LevelAdmission level) {
                if (level.getLevelIndex() != 0) return settings.reservePeriodSec();
                Object answer = leafAnswers.pollFirst();
                if (answer instanceof RuntimeException e) throw e;
                return answer == null ? settings.reservePeriodSec() : (Double) answer;
            }
        };
    }

    private CallFlowEngine<VoiceFlow.Call> engineOf(VoiceFlow flow) {
        CallFlowEngine<VoiceFlow.Call> engine = CallFlowEngine.of(flow).child(Wire.TYPE, Wire::new).start();
        engines.add(engine);
        return engine;
    }

    private static VoiceFlow.Call answered(CallFlowEngine<VoiceFlow.Call> engine, String id) throws Exception {
        VoiceFlow.Call call = Scene.call(id, "10.0.0.7", "01712345678");
        assertThat(engine.launch(call).launched()).isTrue();
        engine.awaitSettled(id, 5, TimeUnit.SECONDS);
        tell(engine, id, new Wire.Answer());
        assertThat(engine.stateOf(id)).isEqualTo(CallState.ACTIVE);
        return call;
    }

    private static void tell(CallFlowEngine<?> engine, String callId, StatemachineEvent event) throws Exception {
        engine.deliver(callId, event).get(5, TimeUnit.SECONDS);
        engine.awaitSettled(callId, 5, TimeUnit.SECONDS);
    }

    private List<CdrEvent> cdrOf(String callId) throws InterruptedException {
        Scene.await("the CDR of " + callId, () -> !scene.cdrs.of(callId).isEmpty());
        return scene.cdrs.of(callId).get(0).tiers();
    }

    @Test
    void aPartialWindow_cutsTheCallWhenTheMoneyEnds_beforeTheNextTick_andTheEndSettlesTheRealTime() throws Exception {
        CallFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        CallFlowEngine<VoiceFlow.Call> engine = engineOf(scriptedVoice(settings, new ArrayDeque<>(List.of(0.4))));   // the tick at 1 s: 0.4 s left
        VoiceFlow.Call call = answered(engine, "rw-1");

        List<CdrEvent> tiers = cdrOf("rw-1");

        assertThat(tiers).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(CallCause.BALANCE_EXHAUSTED));
        long ranMs = call.endedAtMs - call.activatedAtMs;
        assertThat(ranMs).as("cut ~1.4 s after the answer: at the end of the final window, not at the 2 s tick").isBetween(1300L, 1950L);
        assertThat(tiers.get(0).inPartnerCost).as("one started minute, settled by the end on the real time").isEqualByComparingTo("0.60");
        assertThat(call.reservesClosed).isTrue();
        assertThat(scene.ledger.timesAsked("settle")).isEqualTo(2);
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void nothingLeftAtATier_cutsTheCallAtTheTick() throws Exception {
        CallFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        CallFlowEngine<VoiceFlow.Call> engine = engineOf(scriptedVoice(settings, new ArrayDeque<>(List.of(0.0))));
        VoiceFlow.Call call = answered(engine, "rw-2");

        List<CdrEvent> tiers = cdrOf("rw-2");

        assertThat(tiers).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(CallCause.BALANCE_EXHAUSTED));
        assertThat(call.endedAtMs - call.activatedAtMs).as("cut at the first tick").isBetween(900L, 1500L);
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void aWholeWindowEveryTick_keepsTheCadence_andTheHangupEndsTheCallNormally() throws Exception {
        CallFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        CallFlowEngine<VoiceFlow.Call> engine = engineOf(scriptedVoice(settings, new ArrayDeque<>(List.of(1.0, 1.0, 1.0))));
        answered(engine, "rw-3");
        Thread.sleep(2300);                                                              // two ticks, both whole
        assertThat(engine.stateOf("rw-3")).isEqualTo(CallState.ACTIVE);

        tell(engine, "rw-3", new Wire.Hangup(CallCause.NORMAL_CLEARING, 2));

        assertThat(cdrOf("rw-3")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(CallCause.NORMAL_CLEARING));
    }

    @Test
    void aRenewalThatThrows_neverCutsTheCall() throws Exception {
        CallFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        Deque<Object> script = new ArrayDeque<>(List.of(new IllegalStateException("the billing road is down")));
        CallFlowEngine<VoiceFlow.Call> engine = engineOf(scriptedVoice(settings, script));
        answered(engine, "rw-4");
        Thread.sleep(1400);                                                              // the tick that throws has passed
        assertThat(engine.stateOf("rw-4")).isEqualTo(CallState.ACTIVE);

        tell(engine, "rw-4", new Wire.Hangup(CallCause.NORMAL_CLEARING, 1));

        assertThat(cdrOf("rw-4")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(CallCause.NORMAL_CLEARING));
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void theDefaultRenewal_holdsTheNextWindowThroughTheLedger_thePeriodWhenHeld_zeroWhenRefused() {
        scene.ledger.fund("res_44", 701, "1.50");                                        // the first minute and one renewal
        VoiceFlow voice = scene.voice(Scene.settings(4).withReservePeriodSec(60));
        VoiceFlow.Call call = Scene.call("rw-5", "10.0.0.7", "01712345678");
        assertThat(voice.preprocess(call)).isNull();
        assertThat(voice.admit(call, StepMode.LIVE).accepted()).isTrue();

        assertThat(voice.renewReserves(call)).as("the second minute is held at both tiers").isEqualTo(60.0);
        assertThat(voice.renewReserves(call)).as("the third cannot be: 0.30 is left of 1.50").isZero();

        assertThat(scene.ledger.journal()).extracting(InMemoryLedger.Entry::verb, InMemoryLedger.Entry::reference)
            .contains(tuple("reserve", "rw-5#L0#W2"), tuple("refused", "rw-5#L0#W3"));
        assertThat(call.levels.get(0).getReservationCount()).isEqualTo(2);
    }

    @Test
    void theDefaultRenewal_zeroRatedOrNotRenewing_keepsTheCadence_aFaultNeverCuts() {
        VoiceFlow free = new VoiceFlow(scene.kit(Scene.settings(4).withReservePeriodSec(60)), Map.of("10.0.0.7", 701),
            Map.of("res_44#701", new BigDecimal("0.60"), "btcl#44", new BigDecimal("0.40")), List.of(new VoiceFlow.Route("017", "GP-trunk", 5))) {
            @Override protected TierRate rateNextWindow(Call call, LevelAdmission level) { return level.getLevelIndex() == 0 ? TierRate.free() : null; }
        };
        VoiceFlow.Call call = Scene.call("rw-6", "10.0.0.7", "01712345678");
        assertThat(free.preprocess(call)).isNull();
        assertThat(free.admit(call, StepMode.LIVE).accepted()).isTrue();
        long reservesBefore = scene.ledger.count("reserve");

        assertThat(free.renewReserves(call)).as("a zero-rated tier and a tier that does not renew both keep the cadence").isEqualTo(60.0);
        assertThat(scene.ledger.count("reserve")).as("the ledger was not asked").isEqualTo(reservesBefore);

        scene.ledger.faultOn("res_44", 701);
        VoiceFlow voice = scene.voice(Scene.settings(4).withReservePeriodSec(60));
        VoiceFlow.Call faulting = Scene.call("rw-7", "10.0.0.7", "01712345678");
        scene.ledger.heal();
        assertThat(voice.preprocess(faulting)).isNull();
        assertThat(voice.admit(faulting, StepMode.LIVE).accepted()).isTrue();
        scene.ledger.faultOn("res_44", 701);

        assertThat(voice.renewReserves(faulting)).as("a ledger fault never cuts a call").isEqualTo(60.0);
    }
}
