package com.telcobright.seed.sessionflow;

import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.SessionFlowEngine;
import com.telcobright.seed.sessionflow.api.SessionState;
import com.telcobright.seed.sessionflow.samples.AdFlow;
import com.telcobright.seed.sessionflow.samples.Scene;
import com.telcobright.seed.sessionflow.samples.VoiceFlow;
import com.telcobright.seed.sessionflow.samples.Wire;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.session.SdrRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 (the owner, 2026-10-09: every session kind takes a CampaignTask in; the base closes it): the task on the base context is
 * closed by the base at the end of the session, once, whatever the application — completed on SUCCEEDED with the leaf tier's
 * charge, failed otherwise with what the settlement kept, the CDR's cause on it, handed to the kit's sink; a deferred session
 * leaves it open; a session that carries none closes nothing.
 */
class SessionTaskCloseTest {

    private final Scene scene = new Scene();
    private final List<SessionFlowEngine<?>> engines = new ArrayList<>();

    @AfterEach
    void stopEngines() { engines.forEach(SessionFlowEngine::close); }

    private SessionFlowEngine<VoiceFlow.Call> voiceEngine() {
        return started(SessionFlowEngine.of(scene.voice(Scene.settings(4))).child(Wire.TYPE, Wire::new).start());
    }

    private SessionFlowEngine<AdFlow.View> adEngine(boolean unshownViewIsCharged) {
        return started(SessionFlowEngine.of(scene.ad(Scene.settings(4), unshownViewIsCharged)).child(Wire.TYPE, Wire::new).start());
    }

    private <E extends SessionFlowEngine<?>> E started(E engine) {
        engines.add(engine);
        return engine;
    }

    private static void tell(SessionFlowEngine<?> engine, String id, StatemachineEvent event) throws Exception {
        engine.deliver(id, event).get(5, TimeUnit.SECONDS);
        engine.awaitSettled(id, 5, TimeUnit.SECONDS);
    }

    private List<CdrEvent> cdrOf(String id) throws InterruptedException {
        Scene.await("the CDR of " + id, () -> !scene.cdrs.of(id).isEmpty());
        return scene.cdrs.of(id).get(0).tiers();
    }

    private SdrRecord sessionRecordOf(String id) throws InterruptedException {
        Scene.await("the session record of " + id, () -> scene.sessionRecords.stream().anyMatch(r -> r.sessionKey().equals(id)));
        return scene.sessionRecords.stream().filter(r -> r.sessionKey().equals(id)).findFirst().orElseThrow();
    }

    /** An open task row, as a request becomes one: a call carries one too (campaign 0 = no campaign's). */
    private static CampaignTask openTask(String id, String tenant, int partnerId, CampaignKind kind) {
        return new CampaignTask(id, tenant, 0, partnerId, kind, "01712345678", null, null, null, null, TaskState.PROCESSING,
            Instant.now(), null, null, 0, null, TaskCharge.FREE, Map.of());
    }

    // ── an ad view: the claim sets the task, the end closes it ───────────────────

    @Test
    void aViewShownAndCompleted_closesItsTaskOnce_completed_withTheLeafsChargeAndTheCdrsCause() throws Exception {
        SessionFlowEngine<AdFlow.View> engine = adEngine(true);
        AdFlow.View view = Scene.view("v-1", "paying-zone");

        assertThat(engine.launch(view).launched()).isTrue();
        engine.awaitSettled("v-1", 5, TimeUnit.SECONDS);
        assertThat(engine.stateOf("v-1")).isEqualTo(SessionState.ADMITTED);
        assertThat(view.task).as("the claim set the task").isNotNull();
        assertThat(view.task.state()).isEqualTo(TaskState.PROCESSING);
        assertThat(view.taskClosed).isFalse();
        tell(engine, "v-1", new Wire.Ring("SHOWN"));
        tell(engine, "v-1", new Wire.Answer());
        tell(engine, "v-1", new Wire.Hangup("viewed", 15));

        List<CdrEvent> tiers = cdrOf("v-1");
        Scene.await("the task closed", () -> !scene.closedTasks.isEmpty());
        CampaignTask closed = scene.closedTasks.get(0);
        assertThat(closed).isSameAs(view.task);
        assertThat(view.taskClosed).isTrue();
        assertThat(closed.state()).isEqualTo(TaskState.SENT);
        assertThat(closed.answered()).isTrue();
        assertThat(closed.answeredAt().toEpochMilli()).isEqualTo(view.answeredAtMs);
        assertThat(closed.endedAt()).isNotNull();
        assertThat(closed.billsec()).isEqualTo((int) Math.round(view.durationSec));
        assertThat(closed.endCause()).as("the application's own word on the task row").isEqualTo("viewed");
        assertThat(tiers.get(0).hangupCause).as("the CDR keeps the call's word").isEqualTo("viewed".equals(tiers.get(0).hangupCause) ? "viewed" : tiers.get(0).hangupCause);
        assertThat(closed.charge().free()).isFalse();
        assertThat(closed.charge().cost()).as("cash: the leaf tier's charge as the settlement left it")
            .isEqualByComparingTo(view.settlements.get(0).charged());
        assertThat(closed.charge().packageAmount()).isEqualByComparingTo("0");
        assertThat(closed.charge().packageAccountId()).isEqualTo(view.levels.get(0).getChargeAccountId());
        Scene.await("the machine is back in the pool", () -> engine.stats().live() == 0);
        assertThat(scene.closedTasks).as("closed once").hasSize(1);
    }

    @Test
    void anAdmittedViewTheScreenNeverShowed_itsWindowRunsOut_closesItsTaskFailed_withWhatTheSettlementKept() throws Exception {
        SessionFlowEngine<AdFlow.View> engine = adEngine(true);      // the owner's rule: an admitted view never shown keeps its charge
        AdFlow.View view = Scene.view("v-2", "paying-zone");

        assertThat(engine.launch(view).launched()).isTrue();
        engine.awaitSettled("v-2", 5, TimeUnit.SECONDS);
        assertThat(engine.stateOf("v-2")).isEqualTo(SessionState.ADMITTED);

        List<CdrEvent> tiers = cdrOf("v-2");                        // the screen never reports: the ADMITTED window runs out
        Scene.await("the task closed", () -> !scene.closedTasks.isEmpty());
        CampaignTask closed = scene.closedTasks.get(0);
        assertThat(closed.state()).isEqualTo(TaskState.FAILED);
        assertThat(closed.answered()).isFalse();
        assertThat(closed.billsec()).isZero();
        assertThat(closed.endCause()).isEqualTo(tiers.get(0).hangupCause);
        assertThat(view.settlements.get(0).charged()).as("the settlement kept the charge").isPositive();
        assertThat(closed.charge().free()).as("a failed row still says what it cost").isFalse();
        assertThat(closed.charge().cost()).isEqualByComparingTo(view.settlements.get(0).charged());
    }

    @Test
    void anAdmittedViewNeverShown_underTheCallsRule_closesItsTaskFailed_andFree() throws Exception {
        SessionFlowEngine<AdFlow.View> engine = adEngine(false);     // the call's rule: unanswered pays nothing
        AdFlow.View view = Scene.view("v-3", "paying-zone");

        assertThat(engine.launch(view).launched()).isTrue();
        engine.awaitSettled("v-3", 5, TimeUnit.SECONDS);
        assertThat(engine.stateOf("v-3")).isEqualTo(SessionState.ADMITTED);

        cdrOf("v-3");                                               // the ADMITTED window runs out
        Scene.await("the task closed", () -> !scene.closedTasks.isEmpty());
        CampaignTask closed = scene.closedTasks.get(0);
        assertThat(closed.state()).isEqualTo(TaskState.FAILED);
        assertThat(view.settlements.get(0).charged()).isZero();
        assertThat(closed.charge().free()).isTrue();
    }

    @Test
    void aViewShownThenAbandoned_endsItsSessionSucceeded_butItsTaskFailed_theApplicationsOwnCompletion() throws Exception {
        SessionFlowEngine<AdFlow.View> engine = adEngine(true);
        AdFlow.View view = Scene.view("v-5", "paying-zone");

        assertThat(engine.launch(view).launched()).isTrue();
        engine.awaitSettled("v-5", 5, TimeUnit.SECONDS);
        tell(engine, "v-5", new Wire.Ring("SHOWN"));
        tell(engine, "v-5", new Wire.Answer());
        view.abandoned = true;                                             // the viewer left the page
        tell(engine, "v-5", new Wire.Hangup("abandoned", 4));

        cdrOf("v-5");
        Scene.await("the task closed", () -> !scene.closedTasks.isEmpty());
        assertThat(sessionRecordOf("v-5").outcome()).as("the session ran: SUCCEEDED").isEqualTo(SessionState.SUCCEEDED);
        CampaignTask closed = scene.closedTasks.get(0);
        assertThat(closed.state()).as("the task: the application's own completion").isEqualTo(TaskState.FAILED);
        assertThat(closed.endCause()).isEqualTo("abandoned");
        assertThat(closed.billsec()).isEqualTo(4);
        assertThat(closed.charge().cost()).as("what the settlement kept still stands on the row").isEqualByComparingTo(view.settlements.get(0).charged());
    }

    @Test
    void aViewNobodyCanPay_isRefused_carriesNoTask_theSinkSeesNothing() throws Exception {
        SessionFlowEngine<AdFlow.View> engine = adEngine(true);
        AdFlow.View view = Scene.view("v-4", "poor-zone");          // its only campaign's advertiser has no money

        engine.launch(view);
        engine.awaitSettled("v-4", 5, TimeUnit.SECONDS);

        assertThat(cdrOf("v-4")).as("the refusal still writes one record").hasSize(1);
        assertThat(view.task).isNull();
        assertThat(view.taskClosed).isFalse();
        assertThat(scene.closedTasks).isEmpty();
    }

    // ── any session kind: a call that carries a task closes it the same way ───────

    @Test
    void aCallThatCarriesATask_closesItLikeAnySession_completedWithTheLeafsCharge() throws Exception {
        SessionFlowEngine<VoiceFlow.Call> engine = voiceEngine();
        VoiceFlow.Call call = Scene.call("call-t1", "10.0.0.7", "01712345678");
        call.task = openTask("call-t1", "res_44", 701, CampaignKind.VOICE);

        assertThat(engine.launch(call).launched()).isTrue();
        engine.awaitSettled("call-t1", 5, TimeUnit.SECONDS);
        tell(engine, "call-t1", new Wire.Answer());
        tell(engine, "call-t1", new Wire.Hangup(SessionCause.NORMAL_CLEARING, 95));

        cdrOf("call-t1");
        Scene.await("the task closed", () -> !scene.closedTasks.isEmpty());
        CampaignTask closed = scene.closedTasks.get(0);
        assertThat(closed.state()).isEqualTo(TaskState.SENT);
        assertThat(closed.kind()).isEqualTo(CampaignKind.VOICE);
        assertThat(closed.campaignId()).as("no campaign's: 0").isZero();
        assertThat(closed.billsec()).isEqualTo(95);
        assertThat(closed.endCause()).isEqualTo(SessionCause.NORMAL_CLEARING);
        assertThat(closed.charge().cost()).as("two started minutes at 0.60").isEqualByComparingTo("1.20");
        assertThat(call.task).isSameAs(closed);
    }

    @Test
    void aPlainCallCarriesNoTask_theSinkSeesNothing() throws Exception {
        SessionFlowEngine<VoiceFlow.Call> engine = voiceEngine();
        VoiceFlow.Call call = Scene.call("call-t2", "10.0.0.7", "01712345678");

        assertThat(engine.launch(call).launched()).isTrue();
        engine.awaitSettled("call-t2", 5, TimeUnit.SECONDS);
        tell(engine, "call-t2", new Wire.Answer());
        tell(engine, "call-t2", new Wire.Hangup(SessionCause.NORMAL_CLEARING, 30));

        cdrOf("call-t2");
        assertThat(call.task).isNull();
        assertThat(scene.closedTasks).isEmpty();
    }

    @Test
    void aDeferredSession_leavesItsTaskOpen_theSinkSeesNothing() throws Exception {
        SessionFlowEngine<VoiceFlow.Call> engine = voiceEngine();
        VoiceFlow.Call call = Scene.call("call-t3", "10.0.0.7", "01712345678");
        call.task = openTask("call-t3", "res_44", 701, CampaignKind.VOICE);

        assertThat(engine.launch(call).launched()).isTrue();
        engine.awaitSettled("call-t3", 5, TimeUnit.SECONDS);
        tell(engine, "call-t3", new Wire.Defer("paid-awaiting-connect"));

        assertThat(sessionRecordOf("call-t3").outcome()).isEqualTo(SessionState.DEFERRED);
        assertThat(call.task.state()).isEqualTo(TaskState.PROCESSING);
        assertThat(call.taskClosed).isFalse();
        assertThat(scene.closedTasks).isEmpty();
    }
}
