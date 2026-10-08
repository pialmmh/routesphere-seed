package com.telcobright.seed.sessionflow;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.ClosedSpan;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.SessionFlowEngine;
import com.telcobright.seed.sessionflow.dependencies.SessionFlowSettings;
import com.telcobright.seed.sessionflow.api.SessionState;
import com.telcobright.seed.sessionflow.samples.Scene;
import com.telcobright.seed.sessionflow.samples.VoiceFlow;
import com.telcobright.seed.sessionflow.samples.Wire;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import com.telcobright.statewalk.event.StatemachineEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * O4 (the wifi architect's B17, 2026-10-09): when the leaf tier's account cannot fund the next window and the application names the
 * next one, the base ROTATES the tier — the current account settled for everything it held and closed as a span, a fresh series on the
 * next account with its first window held at once — and the CDR carries ONE RECORD PER ACCOUNT, {@code <sid>.<n>}, the same callId;
 * the tiers above keep their one record. A next account that cannot fund its first window is the cut, as no next account is.
 */
class SessionFlowRotationTest {

    private final Scene scene = new Scene();
    private final List<SessionFlowEngine<?>> engines = new ArrayList<>();
    private final RecordingJournal journal = new RecordingJournal();

    @AfterEach
    void stopEngines() { engines.forEach(SessionFlowEngine::close); }

    private SessionFlowEngine<VoiceFlow.Call> engineOf(VoiceFlow flow) {
        SessionFlowEngine<VoiceFlow.Call> engine = SessionFlowEngine.of(flow).child(Wire.TYPE, Wire::new).start();
        engines.add(engine);
        return engine;
    }

    private static void tell(SessionFlowEngine<?> engine, String id, StatemachineEvent event) throws Exception {
        engine.deliver(id, event).get(5, TimeUnit.SECONDS);
        engine.awaitSettled(id, 5, TimeUnit.SECONDS);
    }

    private List<CdrEvent> cdrOf(String id) throws InterruptedException {
        Scene.await("the CDR of " + id, () -> !scene.cdrs.of(id).isEmpty());
        assertThat(scene.cdrs.of(id)).as("one message per session").hasSize(1);
        return scene.cdrs.of(id).get(0).tiers();
    }

    private VoiceFlow.Call answered(SessionFlowEngine<VoiceFlow.Call> engine, String id) throws Exception {
        VoiceFlow.Call call = Scene.call(id, "10.0.0.7", "01712345678");
        assertThat(engine.launch(call).launched()).isTrue();
        engine.awaitSettled(id, 5, TimeUnit.SECONDS);
        tell(engine, id, new Wire.Answer());
        assertThat(engine.stateOf(id)).isEqualTo(SessionState.ACTIVE);
        return call;
    }

    private static CdrEvent record(List<CdrEvent> tiers, String uuid) {
        return tiers.stream().filter(c -> uuid.equals(c.channelCallUuid)).findFirst().orElseThrow(() -> new AssertionError("no record " + uuid));
    }

    @Test
    void O4_whenThePurchaseCannotFundTheNextWindow_theSessionGoesOnOnTheNextOne_oneRecordPerAccount_sameCallId() throws Exception {
        SessionFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        scene.ledger.fund("res_44", 701, "0.60")                                       // the first account: one window
            .fundAccount("res_44", 701, 9001L, "1.20")                                // the next purchase: two windows
            .fundAccount("res_44", 701, 9002L, "0.60");                               // the one after: one window
        scene.withJournal(journal);
        VoiceFlow voice = scene.voice(settings).queue("res_44", 701, 9001L, 9002L);
        SessionFlowEngine<VoiceFlow.Call> engine = engineOf(voice);
        VoiceFlow.Call call = answered(engine, "rot-1");

        List<CdrEvent> tiers = cdrOf("rot-1");                                       // the money ends after the third account: the cut

        assertThat(call.endCause).isEqualTo(SessionCause.BALANCE_EXHAUSTED);
        assertThat(call.closedSpans).extracting(ClosedSpan::spanNo).containsExactly(1, 2);
        assertThat(call.spanNo()).isEqualTo(3);
        assertThat(call.levels.get(0).getChargeAccountId()).isEqualTo(9002L);
        assertThat(call.levels.get(0).getDebitReference()).isEqualTo("rot-1.3#L0");
        assertThat(call.closedSpans.get(1).level().getDebitReference()).isEqualTo("rot-1.2#L0");
        assertThat(call.closedSpans.get(1).settlement().charged()).as("two windows held, both used").isEqualByComparingTo("1.20");
        assertThat(call.closedSpans.get(0).settlement().charged()).isEqualByComparingTo("0.60");

        assertThat(tiers).extracting(c -> c.channelCallUuid).containsExactly("rot-1.1", "rot-1.2", "rot-1.3", "rot-1");
        assertThat(tiers).allSatisfy(c -> {
            assertThat(c.callId).as("the spans correlate by the session's callId").isEqualTo("rot-1");
            assertThat(c.hangupCause).isEqualTo(SessionCause.BALANCE_EXHAUSTED);
        });
        CdrEvent span1 = record(tiers, "rot-1.1"), span2 = record(tiers, "rot-1.2"), span3 = record(tiers, "rot-1.3"), top = record(tiers, "rot-1");
        assertThat(List.of(span1, span2, span3)).allSatisfy(c -> {
            assertThat(c.tenant).isEqualTo("res_44");
            assertThat(c.inPartnerId).isEqualTo(701);
            assertThat(c.answerTime).isNotNull();
        });
        assertThat(span1.idPackageAccount).isEqualTo(call.closedSpans.get(0).level().getChargeAccountId());
        assertThat(span1.inPartnerCost).isEqualByComparingTo("0.60");
        assertThat(span2.idPackageAccount).isEqualTo(9001L);
        assertThat(span2.inPartnerCost).isEqualByComparingTo("1.20");
        assertThat(span3.idPackageAccount).isEqualTo(9002L);
        assertThat(span3.inPartnerCost).as("the last span charges its own seconds: one started minute").isEqualByComparingTo("0.60");
        assertThat(span1.durationSec.add(span2.durationSec).add(span3.durationSec).doubleValue()).as("the spans' seconds make the session's")
            .isCloseTo(call.durationSec, within(0.01));
        assertThat(top.tenant).isEqualTo("btcl");
        assertThat(top.inPartnerId).isEqualTo(44);
        assertThat(top.inPartnerCost).as("the tier above does not split: one started minute at 0.40").isEqualByComparingTo("0.40");
        assertThat(top.durationSec).isEqualByComparingTo(BigDecimal.valueOf(call.durationSec));

        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("0.00");
        assertThat(scene.ledger.balanceOfAccount("res_44", 701, 9001L)).isEqualByComparingTo("0.00");
        assertThat(scene.ledger.balanceOfAccount("res_44", 701, 9002L)).isEqualByComparingTo("0.00");
        assertThat(scene.ledger.balanceOf("btcl", 44)).as("four windows held, one minute charged, the rest back").isEqualByComparingTo("99.60");
        assertThat(scene.ledger.openReserves()).isZero();
        assertThat(scene.ledger.timesAsked("settle")).as("two spans settled at their rotation, two tiers at the end").isEqualTo(4);
        assertThat(journal.released).as("the settled series are no longer in the air: the next start leaves them")
            .contains("rot-1#L0", "rot-1.2#L0", "rot-1.2#L0#W2");
    }

    @Test
    void O4_aNextAccountThatCannotFundItsFirstWindow_isTheCut_theClosedSpanStillRecorded() throws Exception {
        SessionFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        scene.ledger.fund("res_44", 701, "0.60").fundAccount("res_44", 701, 9003L, "0.00");
        VoiceFlow voice = scene.voice(settings).queue("res_44", 701, 9003L);
        SessionFlowEngine<VoiceFlow.Call> engine = engineOf(voice);
        VoiceFlow.Call call = answered(engine, "rot-2");

        List<CdrEvent> tiers = cdrOf("rot-2");

        assertThat(call.endCause).isEqualTo(SessionCause.BALANCE_EXHAUSTED);
        assertThat(call.closedSpans).hasSize(1);
        assertThat(call.closedSpans.get(0).settlement().charged()).isEqualByComparingTo("0.60");
        assertThat(call.levels.get(0).getChargeAccountId()).isEqualTo(9003L);
        assertThat(tiers).extracting(c -> c.channelCallUuid).containsExactly("rot-2.1", "rot-2.2", "rot-2");
        assertThat(scene.ledger.count("refused")).as("the first account, then the next one's first window").isEqualTo(2);
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void O4_withNoNextAccount_theCutIsAsBefore_oneRecordPerTier() throws Exception {
        SessionFlowSettings settings = Scene.settings(4).withReservePeriodSec(1);
        scene.ledger.fund("res_44", 701, "0.60");
        SessionFlowEngine<VoiceFlow.Call> engine = engineOf(scene.voice(settings));
        VoiceFlow.Call call = answered(engine, "rot-3");

        List<CdrEvent> tiers = cdrOf("rot-3");

        assertThat(call.endCause).isEqualTo(SessionCause.BALANCE_EXHAUSTED);
        assertThat(call.closedSpans).isEmpty();
        assertThat(tiers).extracting(c -> c.channelCallUuid).containsExactly("rot-3", "rot-3");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    /** A journal that keeps what the base told it. */
    static final class RecordingJournal implements SessionJournal {
        final List<String> released = new CopyOnWriteArrayList<>();
        @Override public void released(String callId, String reference) { released.add(reference); }
        @Override public void handedOver(String callId, long atMs, String records) { }
        @Override public void noted(String callId, long atMs, long answeredAtMs, double billedSec) { }
        @Override public void done(String callId) { }
        @Override public List<Leftover> leftovers() { return List.of(); }
        @Override public String where() { return "recording"; }
    }
}
