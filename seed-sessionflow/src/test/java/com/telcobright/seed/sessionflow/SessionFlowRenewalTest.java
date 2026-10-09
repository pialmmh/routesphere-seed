package com.telcobright.seed.sessionflow;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.SessionFlowEngine;
import com.telcobright.seed.sessionflow.api.SessionState;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.dependencies.SessionFlowSettings;
import com.telcobright.seed.sessionflow.samples.Scene;
import com.telcobright.seed.sessionflow.samples.VoiceFlow;
import com.telcobright.seed.sessionflow.samples.Wire;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
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
class SessionFlowRenewalTest {

    private final Scene scene = new Scene();
    private final List<SessionFlowEngine<?>> engines = new ArrayList<>();

    @AfterEach
    void stopEngines() { engines.forEach(SessionFlowEngine::close); }

    /** The sample voice flow with a scripted renewal at the leaf tier: one answer per tick (a number of seconds, or a throw). */
    private VoiceFlow scriptedVoice(SessionFlowSettings settings, Deque<Object> leafAnswers) {
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

    /** The same, but the application carries the cut out on the wire itself (B9, the call switch's word): it notes it and ends nothing. */
    private VoiceFlow scriptedVoiceCuttingOnTheWire(SessionFlowSettings settings, Deque<Object> leafAnswers, List<String> cuts) {
        return new VoiceFlow(scene.kit(settings), Map.of("10.0.0.7", 701),
            Map.of("res_44#701", new BigDecimal("0.60"), "btcl#44", new BigDecimal("0.40")), List.of(new VoiceFlow.Route("017", "GP-trunk", 5))) {
            @Override
            protected double renewWindowSeconds(Call call, LevelAdmission level) {
                if (level.getLevelIndex() != 0) return settings.reservePeriodSec();
                Object answer = leafAnswers.pollFirst();
                return answer == null ? settings.reservePeriodSec() : (Double) answer;
            }

            @Override
            protected String cutForBalance(Call call) {
                cuts.add(call.sessionKey + " at " + System.currentTimeMillis());
                call.legsKilled = true;                                                 // the switch would kill both legs here
                return null;
            }
        };
    }

    private SessionFlowEngine<VoiceFlow.Call> engineOf(VoiceFlow flow) {
        SessionFlowEngine<VoiceFlow.Call> engine = SessionFlowEngine.of(flow).child(Wire.TYPE, Wire::new).start();
        engines.add(engine);
        return engine;
    }

    @Test
    void B9_aCutTheApplicationCarriesOutOnTheWire_leavesTheCallToTheWiresEnd_noMoreRenewals_theEndSettlesTheRealTime() throws Exception {
        SessionFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        List<String> cuts = new java.util.concurrent.CopyOnWriteArrayList<>();
        SessionFlowEngine<VoiceFlow.Call> engine = engineOf(scriptedVoiceCuttingOnTheWire(settings, new ArrayDeque<>(List.of(0.0)), cuts));
        VoiceFlow.Call call = answered(engine, "b9-1");

        Scene.await("the cut", () -> !cuts.isEmpty());
        Thread.sleep(1300);                                                              // past the next tick: nothing renews, nothing ends the call

        assertThat(engine.stateOf("b9-1")).as("the wire has not ended it yet").isEqualTo(SessionState.ACTIVE);
        assertThat(call.legsKilled).isTrue();
        assertThat(cuts).hasSize(1);
        tell(engine, "b9-1", new Wire.Hangup(SessionCause.NORMAL_CLEARING, 1.2));          // FreeSWITCH reports the killed leg's hangup

        List<CdrEvent> tiers = cdrOf("b9-1");
        assertThat(tiers).allSatisfy(cdr -> assertThat(cdr.hangupCause).as("the wire's cause, not BALANCE_EXHAUSTED").isEqualTo(SessionCause.NORMAL_CLEARING));
        assertThat(tiers.get(0).inPartnerCost).isEqualByComparingTo("0.60");
        Scene.await("the session record", () -> scene.sessionRecords.stream().anyMatch(r -> r.sessionKey().equals("b9-1")));
        assertThat(scene.sessionRecords.stream().filter(r -> r.sessionKey().equals("b9-1")).findFirst().orElseThrow().outcome())
            .as("NORMAL_CLEARING: the call succeeded, as v2's").isEqualTo(SessionState.SUCCEEDED);
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void B9_theFinalWindowsCut_isTheApplicationsToCarryOut_too() throws Exception {
        SessionFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        List<String> cuts = new java.util.concurrent.CopyOnWriteArrayList<>();
        SessionFlowEngine<VoiceFlow.Call> engine = engineOf(scriptedVoiceCuttingOnTheWire(settings, new ArrayDeque<>(List.of(0.4)), cuts));
        VoiceFlow.Call call = answered(engine, "b9-2");

        Scene.await("the cut", () -> !cuts.isEmpty());

        long cutAfterMs = Long.parseLong(cuts.get(0).substring(cuts.get(0).lastIndexOf(' ') + 1)) - call.activatedAtMs;
        assertThat(cutAfterMs).as("at the end of the final window (~1.4 s), not at a tick").isBetween(1300L, 1950L);
        assertThat(engine.stateOf("b9-2")).isEqualTo(SessionState.ACTIVE);
        tell(engine, "b9-2", new Wire.Hangup(SessionCause.NORMAL_CLEARING, 1.4));
        assertThat(cdrOf("b9-2")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(SessionCause.NORMAL_CLEARING));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // B10 — the first renewal at the initial delay (the call switch: 58 s), then every period (60 s)
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** The sample voice flow whose leaf tier notes the moment of every renewal and holds a whole window each time. */
    private VoiceFlow notingTicks(SessionFlowSettings settings, List<Long> ticks) {
        return new VoiceFlow(scene.kit(settings), Map.of("10.0.0.7", 701),
            Map.of("res_44#701", new BigDecimal("0.60"), "btcl#44", new BigDecimal("0.40")), List.of(new VoiceFlow.Route("017", "GP-trunk", 5))) {
            @Override
            protected double renewWindowSeconds(Call call, LevelAdmission level) {
                if (level.getLevelIndex() == 0) ticks.add(System.currentTimeMillis());
                return settings.reservePeriodSec();
            }
        };
    }

    @Test
    void B10_theFirstRenewalComesAtTheInitialDelay_58s_thenEvery60s() throws Exception {
        SessionFlowSettings settings = Scene.settings(4, 3600, 7200).withReservePeriodSec(60).withReserveInitialDelaySec(58);
        List<Long> ticks = new java.util.concurrent.CopyOnWriteArrayList<>();
        SessionFlowEngine<VoiceFlow.Call> engine = engineOf(notingTicks(settings, ticks));
        ManualTimers timers = ManualTimers.installOn(engine.registry());
        answered(engine, "b10-1");

        assertThat(timers.armed(60_000)).as("no tick is armed at the period before the first renewal").isZero();
        timers.fire(timers.newest(58_000));                                             // the first tick, 58 s after the answer
        engine.awaitSettled("b10-1", 5, TimeUnit.SECONDS);
        assertThat(ticks).hasSize(1);
        timers.fire(timers.newest(60_000));                                             // then one every 60 s
        engine.awaitSettled("b10-1", 5, TimeUnit.SECONDS);
        timers.fire(timers.newest(60_000));
        engine.awaitSettled("b10-1", 5, TimeUnit.SECONDS);

        assertThat(ticks).as("three renewals: at 58 s, 118 s, 178 s").hasSize(3);
        assertThat(timers.armed(58_000)).as("the initial delay is armed once").isEqualTo(1);
        assertThat(engine.stateOf("b10-1")).isEqualTo(SessionState.ACTIVE);
    }

    @Test
    void B10_withoutAnInitialDelay_theFirstRenewalIsOnePeriodAfterTheAnswer_asBefore() throws Exception {
        SessionFlowSettings settings = Scene.settings(4, 3600, 7200).withReservePeriodSec(60);
        List<Long> ticks = new java.util.concurrent.CopyOnWriteArrayList<>();
        SessionFlowEngine<VoiceFlow.Call> engine = engineOf(notingTicks(settings, ticks));
        ManualTimers timers = ManualTimers.installOn(engine.registry());
        answered(engine, "b10-2");

        assertThat(settings.firstRenewalSec()).isEqualTo(60);
        timers.fire(timers.newest(60_000));
        engine.awaitSettled("b10-2", 5, TimeUnit.SECONDS);

        assertThat(ticks).as("the first renewal, one period after the answer").hasSize(1);
        assertThat(timers.armed(58_000)).isZero();
    }

    @Test
    void B10_onRealTimers_theFirstTickAtTheInitialDelay_theNextAPeriodLater() throws Exception {
        SessionFlowSettings settings = Scene.settings(4).withReservePeriodSec(2).withReserveInitialDelaySec(1);
        List<Long> ticks = new java.util.concurrent.CopyOnWriteArrayList<>();
        SessionFlowEngine<VoiceFlow.Call> engine = engineOf(notingTicks(settings, ticks));
        VoiceFlow.Call call = answered(engine, "b10-3");

        Scene.await("two renewals", () -> ticks.size() >= 2);
        tell(engine, "b10-3", new Wire.Hangup(SessionCause.NORMAL_CLEARING, 3.2));

        assertThat(ticks.get(0) - call.activatedAtMs).as("the first tick at the initial delay, 1 s").isBetween(900L, 1600L);
        assertThat(ticks.get(1) - ticks.get(0)).as("the next one period later, 2 s").isBetween(1900L, 2600L);
        assertThat(cdrOf("b10-3")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(SessionCause.NORMAL_CLEARING));
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void B10_theSettings_defaultToOnePeriod_keepTheDelayThroughEveryWith_andRefuseANegativeOne() {
        SessionFlowSettings before = Scene.settings(4).withReservePeriodSec(60);
        assertThat(before.reserveInitialDelaySec()).as("the old constructors: no initial delay").isZero();
        assertThat(before.firstRenewalSec()).isEqualTo(60);
        SessionFlowSettings b10 = before.withReserveInitialDelaySec(58);
        assertThat(b10.firstRenewalSec()).isEqualTo(58);
        assertThat(b10.withPool(9).withReservePeriodSec(30).withDebug(true).reserveInitialDelaySec()).isEqualTo(58);
        assertThat(SessionFlowSettings.defaults().firstRenewalSec()).as("no period, no cadence").isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> before.withReserveInitialDelaySec(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static VoiceFlow.Call answered(SessionFlowEngine<VoiceFlow.Call> engine, String id) throws Exception {
        VoiceFlow.Call call = Scene.call(id, "10.0.0.7", "01712345678");
        assertThat(engine.launch(call).launched()).isTrue();
        engine.awaitSettled(id, 5, TimeUnit.SECONDS);
        tell(engine, id, new Wire.Answer());
        assertThat(engine.stateOf(id)).isEqualTo(SessionState.ACTIVE);
        return call;
    }

    private static void tell(SessionFlowEngine<?> engine, String callId, StatemachineEvent event) throws Exception {
        engine.deliver(callId, event).get(5, TimeUnit.SECONDS);
        engine.awaitSettled(callId, 5, TimeUnit.SECONDS);
    }

    private List<CdrEvent> cdrOf(String callId) throws InterruptedException {
        Scene.await("the CDR of " + callId, () -> !scene.cdrs.of(callId).isEmpty());
        return scene.cdrs.of(callId).get(0).tiers();
    }

    @Test
    void aPartialWindow_cutsTheCallWhenTheMoneyEnds_beforeTheNextTick_andTheEndSettlesTheRealTime() throws Exception {
        SessionFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        SessionFlowEngine<VoiceFlow.Call> engine = engineOf(scriptedVoice(settings, new ArrayDeque<>(List.of(0.4))));   // the tick at 1 s: 0.4 s left
        VoiceFlow.Call call = answered(engine, "rw-1");

        List<CdrEvent> tiers = cdrOf("rw-1");

        assertThat(tiers).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(SessionCause.BALANCE_EXHAUSTED));
        long ranMs = call.endedAtMs - call.activatedAtMs;
        assertThat(ranMs).as("cut ~1.4 s after the answer: at the end of the final window, not at the 2 s tick").isBetween(1300L, 1950L);
        assertThat(tiers.get(0).inPartnerCost).as("one started minute, settled by the end on the real time").isEqualByComparingTo("0.60");
        assertThat(call.reservesClosed).isTrue();
        assertThat(scene.ledger.timesAsked("settle")).isEqualTo(2);
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void nothingLeftAtATier_cutsTheCallAtTheTick() throws Exception {
        SessionFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        SessionFlowEngine<VoiceFlow.Call> engine = engineOf(scriptedVoice(settings, new ArrayDeque<>(List.of(0.0))));
        VoiceFlow.Call call = answered(engine, "rw-2");

        List<CdrEvent> tiers = cdrOf("rw-2");

        assertThat(tiers).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(SessionCause.BALANCE_EXHAUSTED));
        assertThat(call.endedAtMs - call.activatedAtMs).as("cut at the first tick").isBetween(900L, 1500L);
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void aWholeWindowEveryTick_keepsTheCadence_andTheHangupEndsTheCallNormally() throws Exception {
        SessionFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        SessionFlowEngine<VoiceFlow.Call> engine = engineOf(scriptedVoice(settings, new ArrayDeque<>(List.of(1.0, 1.0, 1.0))));
        answered(engine, "rw-3");
        Thread.sleep(2300);                                                              // two ticks, both whole
        assertThat(engine.stateOf("rw-3")).isEqualTo(SessionState.ACTIVE);

        tell(engine, "rw-3", new Wire.Hangup(SessionCause.NORMAL_CLEARING, 2));

        assertThat(cdrOf("rw-3")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(SessionCause.NORMAL_CLEARING));
    }

    @Test
    void aRenewalThatThrows_neverCutsTheCall() throws Exception {
        SessionFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        Deque<Object> script = new ArrayDeque<>(List.of(new IllegalStateException("the billing road is down")));
        SessionFlowEngine<VoiceFlow.Call> engine = engineOf(scriptedVoice(settings, script));
        answered(engine, "rw-4");
        Thread.sleep(1400);                                                              // the tick that throws has passed
        assertThat(engine.stateOf("rw-4")).isEqualTo(SessionState.ACTIVE);

        tell(engine, "rw-4", new Wire.Hangup(SessionCause.NORMAL_CLEARING, 1));

        assertThat(cdrOf("rw-4")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(SessionCause.NORMAL_CLEARING));
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void theDefaultRenewal_holdsTheNextWindowThroughTheLedger_thePeriodWhenHeld_zeroWhenRefused() {
        scene.ledger.fund("res_44", 701, "1.50");                                        // the first minute and one renewal
        VoiceFlow voice = scene.voice(Scene.settings(4).withReservePeriodSec(60));
        VoiceFlow.Call call = Scene.call("rw-5", "10.0.0.7", "01712345678");
        assertThat(voice.preprocess(call)).isNull();
        assertThat(voice.admission(call, StepMode.LIVE).accepted()).isTrue();

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
        assertThat(free.admission(call, StepMode.LIVE).accepted()).isTrue();
        long reservesBefore = scene.ledger.count("reserve");

        assertThat(free.renewReserves(call)).as("a zero-rated tier and a tier that does not renew both keep the cadence").isEqualTo(60.0);
        assertThat(scene.ledger.count("reserve")).as("the ledger was not asked").isEqualTo(reservesBefore);

        scene.ledger.faultOn("res_44", 701);
        VoiceFlow voice = scene.voice(Scene.settings(4).withReservePeriodSec(60));
        VoiceFlow.Call faulting = Scene.call("rw-7", "10.0.0.7", "01712345678");
        scene.ledger.heal();
        assertThat(voice.preprocess(faulting)).isNull();
        assertThat(voice.admission(faulting, StepMode.LIVE).accepted()).isTrue();
        scene.ledger.faultOn("res_44", 701);

        assertThat(voice.renewReserves(faulting)).as("a ledger fault never cuts a call").isEqualTo(60.0);
    }
}
