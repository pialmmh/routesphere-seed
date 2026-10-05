package com.telcobright.seed.callflow;

import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.api.CallFlowEngine;
import com.telcobright.seed.callflow.api.CallState;
import com.telcobright.seed.callflow.api.CdrEvent;
import com.telcobright.seed.callflow.api.RoutePlan;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.samples.VoiceFlow;
import com.telcobright.seed.callflow.samples.Wire;
import com.telcobright.statewalk.event.StatemachineEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The re-route ritual of the call switch (C12) on the base: a failed attempt before the answer is recorded, the application's
 * table says REROUTE | RETRY_SAME | FAIL_TERMINAL, the route plan advances, a fresh signaling child is spawned — on the SAME
 * reserve: nothing is re-admitted. The sample's 017 prefix has four hops: GP-trunk, GP-backup, GP-third, GP-never-reached.
 */
class CallFlowRerouteTest {

    private final Scene scene = new Scene();
    private final List<CallFlowEngine<?>> engines = new ArrayList<>();

    @AfterEach
    void stopEngines() { engines.forEach(CallFlowEngine::close); }

    private CallFlowEngine<VoiceFlow.Call> voiceEngine() {
        CallFlowEngine<VoiceFlow.Call> engine = CallFlowEngine.of(scene.voice(Scene.settings(4))).child(Wire.TYPE, Wire::new).start();
        engines.add(engine);
        return engine;
    }

    private static VoiceFlow.Call launched(CallFlowEngine<VoiceFlow.Call> engine, String id, String dialed) throws Exception {
        VoiceFlow.Call call = Scene.call(id, "10.0.0.7", dialed);
        assertThat(engine.launch(call).launched()).isTrue();
        engine.awaitSettled(id, 5, TimeUnit.SECONDS);
        assertThat(engine.stateOf(id)).isEqualTo(CallState.ADMITTED);
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
    void aBusyFarEnd_sendsTheCallOverTheNextHop_onTheSameReserve_andTheCallGoesOn() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine();
        VoiceFlow.Call call = launched(engine, "rr-1", "01712345678");
        assertThat(call.outgoingRoute).isEqualTo("GP-trunk");

        tell(engine, "rr-1", new Wire.Fail("USER_BUSY"));

        assertThat(engine.stateOf("rr-1")).as("still admitted: the next hop is being tried").isEqualTo(CallState.ADMITTED);
        assertThat(call.attempts).isEqualTo(2);
        assertThat(call.outgoingRoute).isEqualTo("GP-backup");
        assertThat(call.outPartnerId).isEqualTo(6);
        assertThat(call.plan().currentIndex()).isEqualTo(1);
        assertThat(call.plan().attempts()).extracting(RoutePlan.Attempt::hopIndex, RoutePlan.Attempt::cause).containsExactly(tuple(0, "USER_BUSY"));
        assertThat(scene.ledger.count("reserve")).as("two tiers reserved once: a re-route never re-reserves").isEqualTo(2);
        assertThat(scene.ledger.count("release")).isZero();

        tell(engine, "rr-1", new Wire.Answer());
        tell(engine, "rr-1", new Wire.Hangup(CallCause.NORMAL_CLEARING, 30));

        List<CdrEvent> tiers = cdrOf("rr-1");
        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).outgoingRoute).isEqualTo("GP-backup");
        assertThat(tiers.get(0).outPartnerId).isEqualTo(6);
        assertThat(tiers.get(0).hangupCause).isEqualTo(CallCause.NORMAL_CLEARING);
        assertThat(tiers.get(0).inPartnerCost).isEqualByComparingTo("0.60");
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("99.40");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void aTemporaryFailure_triesTheSameHopAgain() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine();
        VoiceFlow.Call call = launched(engine, "rr-2", "01712345678");

        tell(engine, "rr-2", new Wire.Fail("NORMAL_TEMPORARY_FAILURE"));

        assertThat(engine.stateOf("rr-2")).isEqualTo(CallState.ADMITTED);
        assertThat(call.attempts).isEqualTo(2);
        assertThat(call.outgoingRoute).as("the same hop").isEqualTo("GP-trunk");
        assertThat(call.plan().currentIndex()).isZero();
        assertThat(call.plan().attempts()).extracting(RoutePlan.Attempt::hopIndex, RoutePlan.Attempt::cause)
            .containsExactly(tuple(0, "NORMAL_TEMPORARY_FAILURE"));

        tell(engine, "rr-2", new Wire.Answer());
        assertThat(engine.stateOf("rr-2")).isEqualTo(CallState.ACTIVE);
    }

    @Test
    void aCauseTheTableCallsTerminal_failsTheCall_withThatCause_andEveryReserveGoesBack() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine();
        VoiceFlow.Call call = launched(engine, "rr-3", "01712345678");

        tell(engine, "rr-3", new Wire.Fail("ORIGINATOR_CANCEL"));

        List<CdrEvent> tiers = cdrOf("rr-3");
        assertThat(tiers).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo("ORIGINATOR_CANCEL"));
        assertThat(call.attempts).isEqualTo(1);
        assertThat(call.plan().attempts()).hasSize(1);
        assertThat(call.plan().currentIndex()).as("a hop was left, the table said no").isZero();
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.balanceOf("btcl", 44)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void rerouteWithNoHopLeft_failsWithTheCause() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine();
        VoiceFlow.Call call = launched(engine, "rr-4", "01812345678");                   // 018 has one route
        assertThat(call.plan().hopCount()).isEqualTo(1);

        tell(engine, "rr-4", new Wire.Fail("USER_BUSY"));

        assertThat(cdrOf("rr-4")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo("USER_BUSY"));
        assertThat(call.attempts).isEqualTo(1);
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void theThirdFailedAttempt_isTheLast_evenWithAHopLeft_theV1Cap() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine();
        VoiceFlow.Call call = launched(engine, "rr-5", "01712345678");                   // 017 has four routes
        assertThat(call.plan().hopCount()).isEqualTo(4);
        assertThat(call.plan().maxAttempts()).isEqualTo(3);

        tell(engine, "rr-5", new Wire.Fail("USER_BUSY"));
        assertThat(call.outgoingRoute).isEqualTo("GP-backup");
        tell(engine, "rr-5", new Wire.Fail("CALL_REJECTED"));
        assertThat(call.outgoingRoute).isEqualTo("GP-third");
        assertThat(call.attempts).isEqualTo(3);
        tell(engine, "rr-5", new Wire.Fail("NO_ANSWER"));

        assertThat(cdrOf("rr-5")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo("NO_ANSWER"));
        assertThat(call.attempts).as("three attempts in all, as v1's getMaxRerouteAttempts").isEqualTo(3);
        assertThat(call.outgoingRoute).as("the fourth hop was never tried").isEqualTo("GP-third");
        assertThat(call.plan().attempts()).extracting(RoutePlan.Attempt::hopIndex, RoutePlan.Attempt::cause)
            .containsExactly(tuple(0, "USER_BUSY"), tuple(1, "CALL_REJECTED"), tuple(2, "NO_ANSWER"));
        assertThat(scene.ledger.count("reserve")).as("one admission for three attempts").isEqualTo(2);
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void anApplicationWithNoTable_failsOnTheFirstFailure_asBefore() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine();
        VoiceFlow.Call call = launched(engine, "rr-6", "01712345678");
        call.protocol = "UNKNOWN";                                                      // the sample's table reads the cause only; the base's default table says FAIL_TERMINAL for everything

        tell(engine, "rr-6", new Wire.Fail("SIP_503"));

        assertThat(cdrOf("rr-6")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo("SIP_503"));
        assertThat(call.attempts).isEqualTo(1);
    }
}
