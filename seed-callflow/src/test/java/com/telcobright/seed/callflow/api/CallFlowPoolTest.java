package com.telcobright.seed.callflow.api;

import com.telcobright.seed.callflow.dependencies.CallFlowSettings;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.samples.VoiceFlow;
import com.telcobright.seed.callflow.samples.Wire;
import com.telcobright.seed.callflow.testkit.InMemoryLedger;
import com.telcobright.statewalk.registry.RejectCause;
import com.telcobright.statewalk.session.SdrRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pool: a fixed number of machines, a full pool refuses at the door, a machine goes back clean, and a call that no
 * deadline ended is killed — and every one of those ends still settles and writes its CDR.
 *
 * <p>This test sits in the engine's package: the killer test needs a killer shorter than a product's settings allow.
 */
class CallFlowPoolTest {

    private final Scene scene = new Scene();
    private final List<CallFlowEngine<?>> engines = new ArrayList<>();

    @AfterEach
    void stopEngines() { engines.forEach(CallFlowEngine::close); }

    private CallFlowEngine<VoiceFlow.Call> voiceEngine(CallFlowSettings settings) {
        CallFlowEngine<VoiceFlow.Call> engine = CallFlowEngine.of(scene.voice(settings)).child(Wire.TYPE, Wire::new).start();
        engines.add(engine);
        return engine;
    }

    private static VoiceFlow.Call launched(CallFlowEngine<VoiceFlow.Call> engine, String id, String sourceIp) throws InterruptedException {
        VoiceFlow.Call call = Scene.call(id, sourceIp, "01712345678");
        assertThat(engine.launch(call).launched()).as("call %s gets a machine", id).isTrue();
        engine.awaitSettled(id, 5, TimeUnit.SECONDS);
        return call;
    }

    private static void hangUp(CallFlowEngine<VoiceFlow.Call> engine, String id) throws Exception {
        engine.deliver(id, new Wire.Hangup("ORIGINATOR_CANCEL", 0)).get(5, TimeUnit.SECONDS);
    }

    private List<CdrEvent> cdrOf(String callId) throws InterruptedException {
        Scene.await("the CDR of " + callId, () -> !scene.cdrs.of(callId).isEmpty());
        return scene.cdrs.of(callId).get(0).tiers();
    }

    @Test
    void thePoolIsFixed_aCallThatFindsItFullIsRefusedAtTheDoor_andHasNoCdr() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(2));
        launched(engine, "p-1", "10.0.0.7");
        launched(engine, "p-2", "10.0.0.7");

        LaunchResult third = engine.launch(Scene.call("p-3", "10.0.0.7", "01712345678"));

        assertThat(third.launched()).isFalse();
        assertThat(third.busy()).isTrue();
        assertThat(third.cause()).isEqualTo(CallCause.BUSY);
        assertThat(engine.stats().live()).isEqualTo(2);
        assertThat(engine.stats().busy()).isEqualTo(1);
        assertThat(engine.isLive("p-3")).isFalse();

        hangUp(engine, "p-1");
        cdrOf("p-1");
        Scene.await("a machine is free again", () -> engine.stats().live() == 1);
        launched(engine, "p-4", "10.0.0.7");

        assertThat(scene.cdrs.of("p-3")).as("a call refused at the door never had a machine: it has no CDR").isEmpty();
        assertThat(scene.ledger.journal()).noneMatch(e -> e.reference().startsWith("p-3"));
    }

    @Test
    void manyCallsRunThroughAFewMachines_noneIsBuiltBeyondThePool_andNothingIsLeftBehind() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(3));

        for (int wave = 0; wave < 10; wave++) {
            for (int i = 0; i < 3; i++) launched(engine, "w-" + wave + "-" + i, "10.0.0.7");
            for (int i = 0; i < 3; i++) hangUp(engine, "w-" + wave + "-" + i);
            Scene.await("wave " + wave + " to end", () -> engine.stats().live() == 0);
        }

        PoolStats stats = engine.stats();
        assertThat(stats.launched()).isEqualTo(30);
        assertThat(stats.ended()).isEqualTo(30);
        assertThat(stats.cdrPublished()).isEqualTo(30);
        assertThat(stats.machinesBuilt()).as("30 calls ran on the pool's 3 machines").isLessThanOrEqualTo(3);
        assertThat(stats.slotsHeld()).isZero();
        assertThat(stats.busy()).isZero();
        assertThat(scene.cdrs.count()).isEqualTo(30);
        assertThat(scene.ledger.openReserves()).isZero();
        assertThat(scene.ledger.balanceOf("res_44", 701)).as("every unanswered call gave its reserve back").isEqualByComparingTo("100.00");
    }

    @Test
    void aMachineFromThePoolStartsTheNextCallClean() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(1));
        VoiceFlow.Call first = launched(engine, "one", "10.0.0.7");
        engine.deliver("one", new Wire.Answer()).get(5, TimeUnit.SECONDS);
        engine.deliver("one", new Wire.Hangup(CallCause.NORMAL_CLEARING, 30)).get(5, TimeUnit.SECONDS);
        cdrOf("one");
        Scene.await("the one machine is back", () -> engine.stats().live() == 0);

        VoiceFlow.Call second = Scene.call("two", "10.9.9.9", "01712345678");        // nobody owns this source: refused
        engine.launch(second);
        List<CdrEvent> tiers = cdrOf("two");

        assertThat(engine.stats().machinesBuilt()).as("both calls ran on the same machine").isEqualTo(1);
        assertThat(first.levels).hasSize(2);
        assertThat(second.levels).as("nothing of the first call is on the second").isEmpty();
        assertThat(second.answeredAtMs).isZero();
        assertThat(second.settlements).isEmpty();
        assertThat(tiers).hasSize(1);
        assertThat(tiers.get(0).hangupCause).isEqualTo(CallCause.PARTNER_NOT_FOUND);
        SdrRecord record = scene.sessionRecords.stream().filter(r -> r.sessionKey().equals("two")).findFirst().orElseThrow();
        assertThat(record.history().get(0).toState()).as("a pooled machine always starts in PREPROCESSING").isEqualTo(CallState.PREPROCESSING);
        assertThat(record.history()).noneMatch(line -> String.valueOf(line).contains("ACTIVE"));
        assertThat(record.activatedAtMs()).isZero();
    }

    @Test
    void aCallIdThatIsStillLiveIsRefused_andThatIsNotBusy() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(2));
        launched(engine, "same", "10.0.0.7");

        LaunchResult again = engine.launch(Scene.call("same", "10.0.0.7", "01712345678"));

        assertThat(again.launched()).isFalse();
        assertThat(again.busy()).isFalse();
        assertThat(again.reason()).isEqualTo(RejectCause.DUPLICATE_ID);
        assertThat(engine.stats().busy()).isZero();
    }

    @Test
    void aHungMachineIsKilled_itStillSettlesByTheRule_writesItsCdr_andGoesBackToThePool() throws Exception {
        VoiceFlow flow = scene.voice(Scene.settings(2));                 // every state deadline is longer than the killer below
        CallFlowEngine<VoiceFlow.Call> engine = CallFlowEngine.of(flow).child(Wire.TYPE, Wire::new).killerAfterSec(2).start();
        engines.add(engine);
        VoiceFlow.Call call = launched(engine, "hung", "10.0.0.3");       // partner 703: one call at a time
        engine.deliver("hung", new Wire.Answer()).get(5, TimeUnit.SECONDS);

        List<CdrEvent> tiers = cdrOf("hung");                            // nobody hangs up: only the killer ends it

        assertThat(tiers).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(CallCause.HUNG_MACHINE));
        assertThat(tiers.get(0).inPartnerCost).as("it was answered: one started minute is charged, not refunded").isEqualByComparingTo("0.60");
        assertThat(call.legsKilled).isTrue();
        assertThat(scene.ledger.openReserves()).isZero();
        Scene.await("the killed machine is back in the pool", () -> engine.stats().live() == 0);
        assertThat(engine.stats().slotsHeld()).isZero();
        launched(engine, "after", "10.0.0.3");                           // the partner's one slot is free again
    }

    @Test
    void aLongCallRenewsItsReserve_andIsCutWhenATierCannotPayTheNextWindow() throws Exception {
        scene.ledger.fund("res_44", 701, "1.50");                       // enough for the first minute and one renewal
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(2).withReservePeriodSec(1));
        launched(engine, "long", "10.0.0.7");
        engine.deliver("long", new Wire.Answer()).get(5, TimeUnit.SECONDS);

        List<CdrEvent> tiers = cdrOf("long");                            // the second renewal cannot be paid: the call is cut

        assertThat(tiers).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(CallCause.BALANCE_EXHAUSTED));
        assertThat(scene.ledger.journal()).extracting(InMemoryLedger.Entry::verb, InMemoryLedger.Entry::reference)
            .contains(org.assertj.core.groups.Tuple.tuple("reserve", "long#L0#W2"), org.assertj.core.groups.Tuple.tuple("refused", "long#L0#W3"));
        assertThat(tiers.get(0).inPartnerCost).as("two seconds talked = one started minute").isEqualByComparingTo("0.60");
        assertThat(scene.ledger.balanceOf("res_44", 701)).as("1.50 − one minute; both reserves reconciled").isEqualByComparingTo("0.90");
        assertThat(scene.ledger.balanceOf("btcl", 44)).isEqualByComparingTo("99.60");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void stoppingTheEngineEndsEveryLiveCall_eachStillSettlesAndWritesItsCdr() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(3));
        launched(engine, "s-1", "10.0.0.7");
        launched(engine, "s-2", "10.0.0.9");

        engine.close();

        assertThat(cdrOf("s-1")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(CallCause.SYSTEM_SHUTDOWN));
        assertThat(cdrOf("s-2")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(CallCause.SYSTEM_SHUTDOWN));
        assertThat(scene.ledger.openReserves()).isZero();
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.balanceOf("btcl", 9)).isEqualByComparingTo("100.00");
    }
}
