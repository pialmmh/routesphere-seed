package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.seed.sessionflow.samples.ad.AdPlayback.AdTimings;
import com.telcobright.seed.sessionflow.samples.bed.Bed;
import com.telcobright.seed.sessionflow.samples.bed.BedContext;
import com.telcobright.seed.sessionflow.samples.bed.Script;
import com.telcobright.statewalk.session.SessionTimings;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.BudgetExhausted;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The AdPlayback machine ALONE under the minimal handler (P1 §H item 2): every row of its dictionary table (design §6) driven through
 * the fake HTTP adapter, the state of BOTH machines and the answers to the phone asserted at each step.
 */
class AdPlaybackTest {

    static final AdTimings LONG = new AdTimings(5_000, 10_000, 10_000, 10_000);
    static final AdTimings SHORT = new AdTimings(5_000, 300, 300, 300);
    static final AdCreative CREATIVE = new AdCreative("c-77", 11, "video", 15, "media/c-77.mp4");

    private final List<Bed<?, ?>> open = new ArrayList<>();
    private final List<AdPlaybackContext> machines = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() { open.forEach(Bed::close); }

    private Bed<AdOut, AdFrame> bed(String name, AdTimings t, Script script) {
        Bed<AdOut, AdFrame> b = new Bed<>(name, script, AdPlayback.spec(t, this::newMachineContext), new AdChannel(), AdFrames::decode);
        open.add(b);
        return b;
    }

    private AdPlaybackContext newMachineContext() { AdPlaybackContext c = new AdPlaybackContext(); machines.add(c); return c; }

    private static AdFrame start() {
        return AdFrame.start("10.10.188.5:7443", "AA:BB:CC:00:11:22", "8801711000001", "dhaka-zone", "kamalapur", "captive-portal", "10.20.0.1");
    }

    @Test
    void a_whole_view_offered_shown_viewed_and_credited_then_the_pooled_machines_serve_the_next_view_clean() throws Exception {
        Bed<AdOut, AdFrame> b = bed("ad-1", LONG, new Script().plan(CREATIVE));

        b.frame("v-1", start());                                                  // POST ad-sessions
        assertEquals("CONNECTING/OFFERED", b.states("v-1"), "IDLE → INIT → ADMITTING → ROUTING → CONNECTING; the machine IDLE → INIT → OFFERED");
        assertEquals(List.of("200 AdSessionView c-77"), b.wire("v-1"), "the hand-over answers the waiting request");
        assertEquals(List.of("onStart:v-1", "admit", "nextHop:0", "onProgress:OFFERED"), b.script.calls);
        AdFacts facts = (AdFacts) b.script.last().facts;
        assertEquals("10.10.188.5:7443", facts.listen(), "the ingress identity is on the facts");
        assertEquals("kamalapur", facts.site());

        b.frame("v-1", AdFrame.shown());
        assertEquals("ACTIVE/SHOWING", b.states("v-1"), "the view's answer is /shown (the owner's ruling): CONNECTING → ACTIVE");
        BedContext sup = b.script.last();
        assertEquals("c-77", sup.answer.remoteParty());
        assertEquals("video", sup.answer.codec());
        assertEquals(0, AdPlayback.watchedSecondsOf(sup.answer), "nothing watched yet at the answer");
        b.frame("v-1", AdFrame.progress(5));
        b.frame("v-1", AdFrame.progress(10));
        assertEquals("ACTIVE/SHOWING", b.states("v-1"), "a beacon is a stay, a progress in ACTIVE");
        assertEquals(2, b.script.count("onProgress:BEACON"));

        b.frame("v-1", AdFrame.viewed(15));
        assertEquals("ACTIVE/VIEWED", b.states("v-1"), "/viewed is a progress: the credit window open");
        assertEquals(1, b.script.count("onProgress:VIEWED"));

        b.frame("v-1", AdFrame.credited());
        assertEquals("-/-", b.states("v-1"), "ACTIVE → SUCCEEDED; both machines final and gone");
        assertEquals(List.of("200 AdSessionView c-77", "200 shown", "200 progress", "200 progress", "200 AdVerdict watched=15", "200 credited"), b.wire("v-1"));
        List<String> tail = b.script.from("onEnd:");
        assertEquals("onEnd:CREDITED:SUBSCRIBER", tail.get(0));
        assertTrue(tail.get(1).startsWith("settle:SUCCEEDED:"), tail.toString());
        assertEquals("record:SUCCEEDED:CREDITED", tail.get(2));
        assertEquals(List.of("IDLE", "INIT", "OFFERED", "SHOWING", "VIEWED", "TERMINATED"), machines.get(0).road);
        b.assertBackInThePools("v-1");

        b.script.calls.clear();                                                   // a second view on the same pooled machines
        b.frame("v-2", start());
        assertEquals("CONNECTING/OFFERED", b.states("v-2"));
        assertEquals(2, machines.size(), "a fresh machine context per view");
        assertEquals(List.of("IDLE", "INIT", "OFFERED"), machines.get(1).road, "nothing of v-1 on v-2's machine");
        assertNull(b.script.last().answer, "nothing of v-1 on v-2's supervisor");
        b.frame("v-2", AdFrame.shown());
        b.frame("v-2", AdFrame.viewed(15));
        b.frame("v-2", AdFrame.credited());
        b.assertBackInThePools("v-2");
    }

    @Test
    void a_refused_admission_answers_204_with_the_cause_and_releases() throws Exception {
        Script s = new Script().plan(CREATIVE);
        s.verdict = Verdict.reject("INSUFFICIENT_BALANCE");
        Bed<AdOut, AdFrame> b = bed("ad-2", LONG, s);
        b.frame("v-x", start());
        assertEquals("-/-", b.states("v-x"));
        assertEquals(List.of("204 X-Ad-Reason INSUFFICIENT_BALANCE"), b.wire("v-x"));
        assertEquals(List.of("onStart:v-x", "admit", "release", "record:FAILED:INSUFFICIENT_BALANCE"), b.script.calls);
        assertEquals(List.of("IDLE", "INIT", "TERMINATED"), machines.get(0).road);
        b.assertBackInThePools("v-x");
    }

    @Test
    void no_creative_is_no_route_and_the_phone_hears_it() throws Exception {
        Bed<AdOut, AdFrame> b = bed("ad-3", LONG, new Script());
        b.frame("v-n", start());
        assertEquals("-/-", b.states("v-n"));
        assertEquals(List.of("204 X-Ad-Reason NO_ROUTE"), b.wire("v-n"));
        assertEquals("record:FAILED:NO_ROUTE", b.script.calls.get(b.script.calls.size() - 1));
    }

    @Test
    void a_view_abandoned_after_it_was_shown_ends_settled_without_a_second_creative() throws Exception {
        Bed<AdOut, AdFrame> b = bed("ad-4", LONG, new Script().plan(CREATIVE, CREATIVE).on(AdPlayback.ABANDONED, Script.OnFailure.REROUTE));
        b.frame("v-a", start());
        b.frame("v-a", AdFrame.shown());
        b.frame("v-a", AdFrame.abandoned("back-button"));
        assertEquals("-/-", b.states("v-a"));
        assertFalse(b.script.calls.stream().anyMatch(c -> c.startsWith("retry:")), "the machine ended: no re-route is asked (CN-2) " + b.script.calls);
        List<String> tail = b.script.from("onEnd:");
        assertEquals("onEnd:ABANDONED:SUBSCRIBER", tail.get(0), "shown = answered: the phone's abandon is the service's end");
        assertTrue(tail.get(1).startsWith("settle:SUCCEEDED:"), tail.toString());
        assertEquals("record:SUCCEEDED:ABANDONED", tail.get(2));
        assertFalse(b.script.calls.contains("release"), "answered: settled, never released");
        assertEquals(List.of("200 AdSessionView c-77", "200 shown", "200 abandoned"), b.wire("v-a"));
        assertEquals(1, b.script.last().attempts);
    }

    @Test
    void the_show_timer_the_stall_timer_and_the_credit_window_each_end_the_view_by_their_table_row() throws Exception {
        Bed<AdOut, AdFrame> b = bed("ad-5", SHORT, new Script().plan(CREATIVE));

        b.frame("v-ns", start());                                                 // offered, never shown
        b.awaitGone("v-ns", 3_000);
        assertEquals("record:FAILED:NOT_SHOWN", last(b));
        assertEquals(List.of("IDLE", "INIT", "OFFERED", "TERMINATED"), machines.get(0).road);

        b.frame("v-st", start());                                                 // shown, then the beacons stop
        b.frame("v-st", AdFrame.shown());
        b.awaitGone("v-st", 3_000);
        assertTrue(b.script.calls.contains("onEnd:STALLED:TIMER"), b.script.calls.toString());
        assertEquals("record:SUCCEEDED:STALLED", last(b), "shown = answered: a stalled view is settled");

        b.frame("v-cw", start());                                                 // viewed, never credited
        b.frame("v-cw", AdFrame.shown());
        b.frame("v-cw", AdFrame.viewed(15));
        b.awaitGone("v-cw", 3_000);
        assertTrue(b.script.calls.contains("onEnd:CREDIT_WINDOW_EXPIRED:TIMER"), b.script.calls.toString());
        assertEquals("record:SUCCEEDED:CREDIT_WINDOW_EXPIRED", last(b), "a viewed ad is settled when its window closes");
        assertEquals(0, b.reg.activeCellCount());
    }

    @Test
    void a_stop_in_the_credit_window_ends_the_view_by_the_supervisor_and_it_is_settled() throws Exception {
        Bed<AdOut, AdFrame> b = bed("ad-6", LONG, new Script().plan(CREATIVE));
        b.frame("v-s", start());
        b.frame("v-s", AdFrame.shown());
        b.frame("v-s", AdFrame.viewed(15));
        b.reg.onInboundEvent("v-s", new BudgetExhausted(System.currentTimeMillis(), "view"));   // as a budget child would publish it
        b.quiet("v-s");
        assertEquals("-/-", b.states("v-s"), "Stop(BALANCE_EXHAUSTED) → TERMINATED → ServiceEnd → SUCCEEDED");
        assertTrue(b.script.calls.contains("onEnd:BALANCE_EXHAUSTED:SUPERVISOR"), b.script.calls.toString());
        assertEquals("record:SUCCEEDED:BALANCE_EXHAUSTED", last(b));
    }

    @Test
    void the_supervisors_deadlines_reach_the_machine_as_a_reject_while_offered_and_a_stop_once_viewed() throws Exception {
        Script s = new Script().plan(CREATIVE);
        s.timings = new SessionTimings(5, 2, 1, 1);                               // CONNECTING 1 s, ACTIVE 1 s
        Bed<AdOut, AdFrame> b = bed("ad-7", LONG, s);

        b.frame("v-r", start());                                                  // offered; the phone never shows it within CONNECTING
        b.awaitGone("v-r", 4_000);
        assertEquals("record:FAILED:TIMEOUT_CONNECTING", last(b));
        assertTrue(b.script.calls.contains("release"), "never answered: released");
        assertEquals(List.of("IDLE", "INIT", "OFFERED", "TERMINATED"), machines.get(0).road, "OFFERED handled the Reject");

        b.script.calls.clear();
        b.frame("v-m", start());                                                  // viewed; the credit window outlives ACTIVE's maximum
        b.frame("v-m", AdFrame.shown());
        b.frame("v-m", AdFrame.viewed(15));
        b.awaitGone("v-m", 4_000);
        assertTrue(b.script.calls.stream().anyMatch(c -> c.startsWith("settle:FAILED:")), "answered: settled, never released " + b.script.calls);
        assertFalse(b.script.calls.contains("release"));
        assertEquals("record:FAILED:MAX_DURATION_REACHED", last(b));
        assertEquals(List.of("IDLE", "INIT", "OFFERED", "SHOWING", "VIEWED", "TERMINATED"), machines.get(1).road, "VIEWED handled the Stop");
    }

    private static String last(Bed<?, ?> b) { return b.script.calls.get(b.script.calls.size() - 1); }
}
