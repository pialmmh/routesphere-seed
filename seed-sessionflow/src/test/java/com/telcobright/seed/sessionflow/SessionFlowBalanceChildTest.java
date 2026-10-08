package com.telcobright.seed.sessionflow;

import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.SessionFlowEngine;
import com.telcobright.seed.sessionflow.api.SessionState;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.dependencies.SessionFlowSettings;
import com.telcobright.seed.sessionflow.samples.Scene;
import com.telcobright.seed.sessionflow.samples.VoiceFlow;
import com.telcobright.seed.sessionflow.samples.Wire;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.session.SdrRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The balance child (the call switch's BalanceTracker on the base): spawned at ADMITTED beside the signaling, it holds the tiers,
 * outlives a re-route, settles when TEARING_DOWN asks and answers the supervisor with the per-tier results. A call that ends on
 * another path is settled by the supervisor's end with the same rule — once, never twice. The sample voice flow settles this way;
 * the ad and the SMS samples settle inline and have no child.
 */
class SessionFlowBalanceChildTest {

    private final Scene scene = new Scene();
    private final List<SessionFlowEngine<?>> engines = new ArrayList<>();

    @AfterEach
    void stopEngines() { engines.forEach(SessionFlowEngine::close); }

    private SessionFlowEngine<VoiceFlow.Call> voiceEngine(SessionFlowSettings settings) {
        SessionFlowEngine<VoiceFlow.Call> engine = SessionFlowEngine.of(scene.voice(settings)).child(Wire.TYPE, Wire::new).start();
        engines.add(engine);
        return engine;
    }

    private static VoiceFlow.Call launched(SessionFlowEngine<VoiceFlow.Call> engine, String id) throws Exception {
        VoiceFlow.Call call = Scene.call(id, "10.0.0.7", "01712345678");
        assertThat(engine.launch(call).launched()).isTrue();
        engine.awaitSettled(id, 5, TimeUnit.SECONDS);
        assertThat(engine.stateOf(id)).isEqualTo(SessionState.ADMITTED);
        return call;
    }

    private static void tell(SessionFlowEngine<?> engine, String callId, StatemachineEvent event) throws Exception {
        engine.deliver(callId, event).get(5, TimeUnit.SECONDS);
        engine.awaitSettled(callId, 5, TimeUnit.SECONDS);
    }

    private List<CdrEvent> cdrOf(String callId) throws InterruptedException {
        Scene.await("the CDR of " + callId, () -> !scene.cdrs.of(callId).isEmpty());
        assertThat(scene.cdrs.of(callId)).hasSize(1);
        return scene.cdrs.of(callId).get(0).tiers();
    }

    private SdrRecord sessionRecordOf(String callId) throws InterruptedException {
        Scene.await("the session record of " + callId, () -> scene.sessionRecords.stream().anyMatch(r -> r.sessionKey().equals(callId)));
        return scene.sessionRecords.stream().filter(r -> r.sessionKey().equals(callId)).findFirst().orElseThrow();
    }

    @Test
    void theBalanceChildSettlesAnAnsweredCall_once_andTheSupervisorTakesThePerTierResults() throws Exception {
        SessionFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(4));
        VoiceFlow.Call call = launched(engine, "bc-1");
        tell(engine, "bc-1", new Wire.Answer());
        tell(engine, "bc-1", new Wire.Hangup(SessionCause.NORMAL_CLEARING, 95));

        List<CdrEvent> tiers = cdrOf("bc-1");
        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).inPartnerCost).isEqualByComparingTo("1.20");
        assertThat(tiers.get(1).inPartnerCost).isEqualByComparingTo("0.80");
        assertThat(sessionRecordOf("bc-1").outcome()).isEqualTo(SessionState.SUCCEEDED);
        assertThat(call.settlements).extracting(TierSettlement::charged).usingElementComparator(BigDecimal::compareTo)
            .as("the per-tier results the child answered are the call's").containsExactly(new BigDecimal("1.20"), new BigDecimal("0.80"));
        assertThat(call.reservesClosed).isTrue();
        assertThat(scene.ledger.timesAsked("settle")).as("every tier settled exactly once, by the child").isEqualTo(2);
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void aReroutedCall_keepsItsBalanceChildAndItsReserve_andSettlesOnceAtTheEnd() throws Exception {
        SessionFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(4));
        VoiceFlow.Call call = launched(engine, "bc-2");

        tell(engine, "bc-2", new Wire.Fail("USER_BUSY"));
        assertThat(engine.stateOf("bc-2")).isEqualTo(SessionState.ADMITTED);
        assertThat(call.attempts).isEqualTo(2);
        assertThat(call.outgoingRoute).isEqualTo("GP-backup");
        tell(engine, "bc-2", new Wire.Answer());
        tell(engine, "bc-2", new Wire.Hangup(SessionCause.NORMAL_CLEARING, 30));

        assertThat(cdrOf("bc-2").get(0).inPartnerCost).isEqualByComparingTo("0.60");
        assertThat(sessionRecordOf("bc-2").outcome()).isEqualTo(SessionState.SUCCEEDED);
        assertThat(scene.ledger.count("reserve")).as("the first attempt's reserve served the second").isEqualTo(2);
        assertThat(scene.ledger.timesAsked("settle")).isEqualTo(2);
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void aCallThatEndsBeforeTheChildSettles_isSettledByTheSupervisorsEnd_withTheSameRule_once() throws Exception {
        SessionFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(4, 1, 60));     // answered for at most 1 s: the deadline, not the child
        VoiceFlow.Call call = launched(engine, "bc-3");
        tell(engine, "bc-3", new Wire.Answer());

        List<CdrEvent> tiers = cdrOf("bc-3");

        assertThat(tiers).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(SessionCause.MAX_DURATION_REACHED));
        assertThat(tiers.get(0).inPartnerCost).as("answered: one started minute, by the same rule").isEqualByComparingTo("0.60");
        assertThat(sessionRecordOf("bc-3").outcome()).isEqualTo(SessionState.FAILED);
        assertThat(call.reservesClosed).isTrue();
        assertThat(scene.ledger.timesAsked("settle")).as("once per tier, never twice").isEqualTo(2);
        assertThat(scene.ledger.openReserves()).isZero();
        Scene.await("the machine and its child are back in the pool", () -> engine.stats().live() == 0);
    }

    @Test
    void anUnansweredCallOnTheChild_paysNothing_andSettlesOnce() throws Exception {
        SessionFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(4));
        launched(engine, "bc-4");

        tell(engine, "bc-4", new Wire.Hangup("ORIGINATOR_CANCEL", 0));

        assertThat(cdrOf("bc-4")).allSatisfy(cdr -> {
            assertThat(cdr.hangupCause).isEqualTo("ORIGINATOR_CANCEL");
            assertThat(cdr.inPartnerCost).isEqualByComparingTo("0");
        });
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.timesAsked("settle")).isEqualTo(2);
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void whoHasAChild_theVoiceSettlesThroughIt_theAdAndTheSmsSettleInline() {
        assertThat(scene.voice(Scene.settings(4)).balanceChildSettles()).isTrue();
        assertThat(scene.voice(Scene.settings(4)).usesBalanceChild()).isTrue();
        assertThat(scene.ad(Scene.settings(4), true).balanceChildSettles()).isFalse();
        assertThat(scene.ad(Scene.settings(4), true).usesBalanceChild()).as("no period, no async settle: the ad has no child, as today").isFalse();
        assertThat(scene.sms(Scene.settings(4)).usesBalanceChild()).isFalse();
        assertThat(scene.ad(Scene.settings(4).withReservePeriodSec(30), true).usesBalanceChild()).as("a period alone brings the child, for the renewal").isTrue();
    }
}
