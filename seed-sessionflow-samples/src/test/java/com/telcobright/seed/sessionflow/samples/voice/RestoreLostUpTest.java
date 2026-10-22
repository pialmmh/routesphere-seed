package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.samples.bed.Crash;
import com.telcobright.seed.sessionflow.samples.bed.CrashingStore;
import com.telcobright.seed.sessionflow.samples.scene.CrashingCdrSink;
import com.telcobright.seed.sessionflow.samples.scene.CrashingLedger;
import com.telcobright.seed.sessionflow.samples.scene.FlowBed;
import com.telcobright.seed.sessionflow.samples.scene.World;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.statewalk.persistence.InMemoryPersistenceProvider;
import com.telcobright.statewalk.persistence.MachineSnapshot;
import com.telcobright.statewalk.persistence.PersistenceProvider;
import com.telcobright.statewalk.persistence.SessionBundle;
import com.telcobright.statewalk.session.SessionTimings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R-1 · the money cases behind finding §10.2 (P1 T10, the architect's follow-up): statewalk restores a session from its last LANDED row,
 * so an {@code up} that crossed a crash — the signaling machine's last word, published to a supervisor whose next element never landed —
 * is lost. Each test asserts what the session SHOULD come to and is {@code @Disabled}: today it is red, and its red output is the evidence
 * for the owner's ruling (P1-T-report §12). Nothing in statewalk or the base is changed.
 *
 * <p>The node's death is ONE switch ({@link Crash}) shared by its store, its ledger and its CDR road: after it, nothing the dead node does
 * reaches the world. Money is made visible: the leaf pays 60.00 a minute (one a second), the reseller 30.00, billed by the second.
 */
class RestoreLostUpTest {

    static final String R1 = "R-1: statewalk restore — an up lost in a crash; waits for the owner's ruling";
    static final String ID = "c-r1";
    static final Map<String, BigDecimal> RATES = Map.of("res_44#701", new BigDecimal("60.00"), "btcl#44", new BigDecimal("30.00"));

    private final World w = new World();
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception { for (int i = open.size() - 1; i >= 0; i--) open.get(i).close(); }

    // ── (a) the call hung up, its settle cut by the crash: billed to ACTIVE's maximum ──────────────────────────────────

    /**
     * The caller talks one second and hangs up. The EslCall's TERMINATED element (its {@code ServiceEnd} already published up) lands; the
     * supervisor's settle waits on a slow ledger and the node dies before it reaches the ledger, so its SUCCEEDED element never lands. The
     * next node restores the supervisor ALONE in ACTIVE (its machine is final): nobody sends the end again.
     */
    @Test
    @Disabled(R1)
    void a_call_hung_up_whose_settle_the_crash_cut_is_billed_to_the_hangup_not_to_actives_maximum() throws Exception {
        long activeMaxSec = 6;
        RoutesphereSettings s = World.settings(activeMaxSec);
        InMemoryPersistenceProvider store = new InMemoryPersistenceProvider();
        Crash crash = new Crash();
        CrashingLedger slowLedger = new CrashingLedger(w.ledger, crash).holdSettlesUntilTheCrash();
        FlowBed<VoiceContext, EslOut, EslFrame> a = node(flow(w.kit(s, slowLedger, new CrashingCdrSink(w.cdrs, crash))), new CrashingStore(store, crash));

        a.frame(ID, VoiceFlowTest.park(ID, World.ESL_BTCL, "10.0.0.7", "01711000002"));
        a.frame(ID, EslFrame.answer(ID, "b-r1", "PCMA"));
        long answeredAt = a.registry().supervisorContextOf(ID).answer.atMs();
        Thread.sleep(1_000);                                                        // the real talk
        a.frame(ID, EslFrame.hangup(ID, "NORMAL_CLEARING"));
        long hungUpAt = System.currentTimeMillis();
        a.channel.inject(ID, EslFrame.hangupComplete(ID, "NORMAL_CLEARING")).toCompletableFuture().get(5, SECONDS);
        await("the supervisor's settle waits in the ledger", () -> slowLedger.settlesHeld() == 1);
        await("the machine's TERMINATED landed, the supervisor's SUCCEEDED did not", () -> row(store).equals("ACTIVE + EslCall TERMINATED"));
        String rowAtTheCrash = row(store);
        crash.now();                                                                // the node dies with its settle in the air
        assertTrue(a.registry().awaitIdle(5, SECONDS));

        FlowBed<VoiceContext, EslOut, EslFrame> b = node(flow(w.kit(s)), store);
        String restoredAs = b.states(ID);
        long restoredAt = System.currentTimeMillis();
        b.awaitGone(ID, (activeMaxSec + 4) * 1000);
        long endedAt = System.currentTimeMillis();

        CdrEvent leaf = w.cdrs.of(ID).get(0).tiers().get(0);
        double realTalk = (hungUpAt - answeredAt) / 1000.0;
        BigDecimal leafPaid = new BigDecimal("100.00").subtract(w.balance("res_44", 701));
        BigDecimal resellerPaid = new BigDecimal("100.00").subtract(w.balance("btcl", 44));
        String numbers = String.format("R-1(a) real talk %.3f s (answer → hangup) · billed %s s · the leaf paid %s (honest: %.2f) · the reseller paid %s"
                + " (honest: %.2f) · the CDR's cause %s · %d CDR message(s) · the row at the crash: %s · restored as %s · ended %.1f s after the restore,"
                + " %.1f s after the answer · the ledger: %s",
            realTalk, leaf.durationSec, leafPaid, realTalk, resellerPaid, realTalk / 2, leaf.hangupCause, w.cdrs.of(ID).size(), rowAtTheCrash, restoredAs,
            (endedAt - restoredAt) / 1000.0, (endedAt - answeredAt) / 1000.0, moneyOf(w.ledger));
        System.out.println(numbers);

        assertEquals("NORMAL_CLEARING", leaf.hangupCause, numbers);
        assertEquals(realTalk, leaf.durationSec.doubleValue(), 0.5, "billed to the hangup — " + numbers);
        assertEquals(realTalk, leafPaid.doubleValue(), 0.5, "the leaf pays its real talk — " + numbers);
    }

    // ── (b) the answer lost: the restored call is unanswered, cut at CONNECTING's deadline and released ─────────────────

    /**
     * The far end answers. The EslCall's ANSWERED element lands; the supervisor is still inside ACTIVE's entry (held there until the crash)
     * and its ACTIVE element never lands. The next node seats the supervisor in CONNECTING and the machine in ANSWERED: the call is up and
     * talking, but the session does not know it was answered.
     */
    @Test
    @Disabled(R1)
    void an_answer_lost_in_a_crash_the_restored_call_stays_answered_is_not_cut_and_is_charged_for_its_talk() throws Exception {
        long connectingSec = 3;
        RoutesphereSettings s = new RoutesphereSettings(8, 2, 80, new SessionTimings(2, 2, connectingSec, 20), 0, 0, 0, false, 500);
        InMemoryPersistenceProvider store = new InMemoryPersistenceProvider();
        Crash crash = new Crash();
        HeldAtTheAnswer flowA = new HeldAtTheAnswer(w.kit(s, new CrashingLedger(w.ledger, crash), new CrashingCdrSink(w.cdrs, crash)), crash);
        FlowBed<VoiceContext, EslOut, EslFrame> a = node(flowA, new CrashingStore(store, crash));

        a.frame(ID, VoiceFlowTest.park(ID, World.ESL_BTCL, "10.0.0.7", "01711000002"));
        a.channel.inject(ID, EslFrame.answer(ID, "b-r1", "PCMA")).toCompletableFuture().get(5, SECONDS);
        long answeredAt = System.currentTimeMillis();
        await("the supervisor is inside ACTIVE's entry", () -> flowA.answersHeld() == 1);
        await("the machine's ANSWERED landed, the supervisor's ACTIVE did not", () -> row(store).equals("CONNECTING + EslCall ANSWERED"));
        String rowAtTheCrash = row(store);
        crash.now();
        assertTrue(a.registry().awaitIdle(5, SECONDS));

        FlowBed<VoiceContext, EslOut, EslFrame> b = node(flow(w.kit(s)), store);
        String restoredAs = b.states(ID);
        b.awaitGone(ID, (connectingSec + 5) * 1000);                                // the caller is still talking: nobody hung up
        long endedAt = System.currentTimeMillis();

        List<String> wireOfB = b.wire(ID);
        CdrEvent leaf = w.cdrs.of(ID).get(0).tiers().get(0);
        String numbers = String.format("R-1(b) answered, then talking for %.1f s until the switch ended it · the row at the crash: %s · restored as %s"
                + " · node B's wire: %s · the CDR's cause %s, answer time %s, billed %s s · the ledger: %s · the leaf paid %s",
            (endedAt - answeredAt) / 1000.0, rowAtTheCrash, restoredAs, wireOfB, leaf.hangupCause, leaf.answerTime, leaf.durationSec, moneyOf(w.ledger),
            new BigDecimal("100.00").subtract(w.balance("res_44", 701)));
        System.out.println(numbers);

        assertFalse(wireOfB.stream().anyMatch(l -> l.startsWith("uuid_kill")), "an answered call is not cut by the switch — " + numbers);
        assertEquals(0, count(w.ledger, "release"), "an answered call is settled, never released (D-A1) — " + numbers);
        assertNotNull(leaf.answerTime, "the CDR knows it was answered — " + numbers);
    }

    /**
     * (b) once more, the caller hanging up before CONNECTING's deadline: the restored machine ends the call and publishes its
     * {@code ServiceEnd} — to a supervisor in CONNECTING, which has no rule for it. The end is lost too; the deadline then fails the
     * session as never answered.
     */
    @Test
    @Disabled(R1)
    void a_hangup_after_a_lost_answer_reaches_the_restored_supervisor_and_the_call_is_charged_for_its_talk() throws Exception {
        long connectingSec = 3;
        RoutesphereSettings s = new RoutesphereSettings(8, 2, 80, new SessionTimings(2, 2, connectingSec, 20), 0, 0, 0, false, 500);
        InMemoryPersistenceProvider store = new InMemoryPersistenceProvider();
        Crash crash = new Crash();
        HeldAtTheAnswer flowA = new HeldAtTheAnswer(w.kit(s, new CrashingLedger(w.ledger, crash), new CrashingCdrSink(w.cdrs, crash)), crash);
        FlowBed<VoiceContext, EslOut, EslFrame> a = node(flowA, new CrashingStore(store, crash));

        a.frame(ID, VoiceFlowTest.park(ID, World.ESL_BTCL, "10.0.0.7", "01711000002"));
        a.channel.inject(ID, EslFrame.answer(ID, "b-r1", "PCMA")).toCompletableFuture().get(5, SECONDS);
        long answeredAt = System.currentTimeMillis();
        await("the supervisor is inside ACTIVE's entry", () -> flowA.answersHeld() == 1);
        await("the machine's ANSWERED landed, the supervisor's ACTIVE did not", () -> row(store).equals("CONNECTING + EslCall ANSWERED"));
        crash.now();
        assertTrue(a.registry().awaitIdle(5, SECONDS));

        FlowBed<VoiceContext, EslOut, EslFrame> b = node(flow(w.kit(s)), store);
        String restoredAs = b.states(ID);
        Thread.sleep(500);                                                          // the caller talks on, then hangs up
        b.frame(ID, EslFrame.hangup(ID, "NORMAL_CLEARING"));
        b.frame(ID, EslFrame.hangupComplete(ID, "NORMAL_CLEARING"));
        long hungUpAt = System.currentTimeMillis();
        String afterTheHangup = b.states(ID);
        b.awaitGone(ID, (connectingSec + 5) * 1000);

        CdrEvent leaf = w.cdrs.of(ID).get(0).tiers().get(0);
        double realTalk = (hungUpAt - answeredAt) / 1000.0;
        String numbers = String.format("R-1(b2) real talk %.3f s (answer on node A → hangup on node B) · restored as %s · after the hangup %s (the"
                + " ServiceEnd reached a supervisor in CONNECTING) · node B's wire %s · the CDR's cause %s, answer time %s, billed %s s · the ledger: %s"
                + " · the leaf paid %s (honest: %.2f)",
            realTalk, restoredAs, afterTheHangup, b.wire(ID), leaf.hangupCause, leaf.answerTime, leaf.durationSec, moneyOf(w.ledger),
            new BigDecimal("100.00").subtract(w.balance("res_44", 701)), realTalk);
        System.out.println(numbers);

        assertEquals("NORMAL_CLEARING", leaf.hangupCause, numbers);
        assertEquals(0, count(w.ledger, "release"), "an answered call is settled, never released — " + numbers);
        assertEquals(realTalk, new BigDecimal("100.00").subtract(w.balance("res_44", 701)).doubleValue(), 0.5, "charged for its talk — " + numbers);
    }

    // ── (c) a restored budget child renewing for a call that already ended ──────────────────────────────────────────

    /**
     * (a) with the budget child (§L.2: a persisting lane renews at most once a minute — the period 60 s; its FIRST renewal 2 s after the
     * answer, B8). The caller talks 1 s and hangs up before the first renewal; the node dies with the settle in the air. The next node
     * seats the supervisor (ACTIVE) and the budget child (still live in the row) — and the child renews a window for a call that ended.
     */
    @Test
    @Disabled(R1)
    void a_restored_budget_child_renews_no_window_for_a_call_that_already_ended() throws Exception {
        long activeMaxSec = 10;
        RoutesphereSettings s = World.settings(activeMaxSec).withReservePeriodSec(60).withReserveInitialDelaySec(2);
        w.ledger.fund("res_44", 701, "1000.00").fund("btcl", 44, "1000.00");
        InMemoryPersistenceProvider store = new InMemoryPersistenceProvider();
        Crash crash = new Crash();
        CrashingLedger slowLedger = new CrashingLedger(w.ledger, crash).holdSettlesUntilTheCrash();
        FlowBed<VoiceContext, EslOut, EslFrame> a = node(flow(w.kit(s, slowLedger, new CrashingCdrSink(w.cdrs, crash))), new CrashingStore(store, crash));

        a.frame(ID, VoiceFlowTest.park(ID, World.ESL_BTCL, "10.0.0.7", "01711000002"));
        a.frame(ID, EslFrame.answer(ID, "b-r1", "PCMA"));
        long answeredAt = a.registry().supervisorContextOf(ID).answer.atMs();
        Thread.sleep(1_000);
        a.frame(ID, EslFrame.hangup(ID, "NORMAL_CLEARING"));
        long hungUpAt = System.currentTimeMillis();
        a.channel.inject(ID, EslFrame.hangupComplete(ID, "NORMAL_CLEARING")).toCompletableFuture().get(5, SECONDS);
        await("the supervisor's settle waits in the ledger", () -> slowLedger.settlesHeld() == 1);
        await("the machine's TERMINATED landed, the supervisor's SUCCEEDED did not", () -> row(store).startsWith("ACTIVE + EslCall TERMINATED"));
        String rowAtTheCrash = row(store);
        crash.now();
        assertTrue(a.registry().awaitIdle(5, SECONDS));
        long windowsBefore = windows(w.ledger);

        FlowBed<VoiceContext, EslOut, EslFrame> b = node(flow(w.kit(s)), store);
        String restoredAs = b.states(ID) + " + Budget " + b.registry().childStateOf(ID, "Budget");
        b.awaitGone(ID, (activeMaxSec + 5) * 1000);

        CdrEvent leaf = w.cdrs.of(ID).get(0).tiers().get(0);
        double realTalk = (hungUpAt - answeredAt) / 1000.0;
        long windowsAfterTheHangup = windows(w.ledger) - windowsBefore;
        String numbers = String.format("R-1(c) real talk %.3f s · the row at the crash: %s · restored as %s · windows reserved after the hangup: %d"
                + " · billed %s s, the CDR's cause %s · the leaf paid %s (honest: %.2f) · the ledger: %s",
            realTalk, rowAtTheCrash, restoredAs, windowsAfterTheHangup, leaf.durationSec, leaf.hangupCause,
            new BigDecimal("1000.00").subtract(w.balance("res_44", 701)), realTalk, moneyOf(w.ledger));
        System.out.println(numbers);

        assertEquals(0, windowsAfterTheHangup, "no window is renewed for a call that ended — " + numbers);
        assertEquals("NORMAL_CLEARING", leaf.hangupCause, numbers);
    }

    // ── the bed ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private VoiceFlow flow(RoutesphereKit kit) {
        return new VoiceFlow(kit, VoiceFlowTest.PARTNERS, RATES, VoiceFlowTest.ROUTES, Set.of(), Map.of(), true);
    }

    private FlowBed<VoiceContext, EslOut, EslFrame> node(VoiceFlow flow, PersistenceProvider store) {
        FlowBed<VoiceContext, EslOut, EslFrame> b = new FlowBed<>(flow, VoiceContext::new, EslCall.spec(VoiceFlowTest.ESL, EslCallContext::new),
            new EslChannel(), EslFrames::decode, store);
        open.add(b);
        return b;
    }

    /** The voice flow, held inside ACTIVE's entry (its {@code onServiceStarted}) until the node dies: its ACTIVE element never lands. */
    static final class HeldAtTheAnswer extends VoiceFlow {
        private final Crash crash;
        private final AtomicInteger held = new AtomicInteger();

        HeldAtTheAnswer(RoutesphereKit kit, Crash crash) {
            super(kit, VoiceFlowTest.PARTNERS, RATES, VoiceFlowTest.ROUTES, Set.of(), Map.of(), true);
            this.crash = crash;
        }

        @Override
        protected void onServiceStarted(VoiceContext call) {
            super.onServiceStarted(call);
            held.incrementAndGet();
            crash.awaitDeath(10_000);
        }

        int answersHeld() { return held.get(); }
    }

    /** The session's row as the store holds it: the supervisor's state + the signaling machine's. */
    private static String row(PersistenceProvider store) {
        MachineSnapshot row = store.load(ID, "CallSupervisor").orElse(null);
        if (row == null) return "no row";
        SessionBundle bundle = SessionBundle.fromRow(row);
        String machine = bundle.children().stream().filter(c -> c.machineId().endsWith("#" + EslCall.TYPE)).map(MachineSnapshot::currentState)
            .findFirst().orElse("-");
        String budget = bundle.children().stream().filter(c -> c.machineId().endsWith("#Budget")).map(MachineSnapshot::currentState)
            .findFirst().map(st -> " + Budget " + st).orElse("");
        return bundle.supervisor().currentState() + " + EslCall " + machine + budget;
    }

    /** The windows the ledger holds for the session beyond the admission's one minute ({@code …#W<n>} references). */
    private static long windows(InMemoryLedger ledger) {
        return ledger.journal().stream().filter(e -> e.verb().equals("reserve") && e.reference().startsWith(ID) && e.reference().contains("#W")).count();
    }

    private static String moneyOf(InMemoryLedger ledger) {
        return ledger.journal().stream().filter(e -> e.reference().startsWith(ID))
            .map(e -> e.verb() + " " + e.tenant() + "#" + e.partnerId() + " " + e.amount()).toList().toString();
    }

    private static long count(InMemoryLedger ledger, String verb) {
        return ledger.journal().stream().filter(e -> e.verb().equals(verb) && e.reference().startsWith(ID)).count();
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) fail("timed out waiting: " + what);
            Thread.sleep(5);
        }
    }
}
