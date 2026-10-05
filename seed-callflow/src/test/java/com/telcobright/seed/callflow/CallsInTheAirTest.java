package com.telcobright.seed.callflow;

import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.api.CallFlowEngine;
import com.telcobright.seed.callflow.api.CallState;
import com.telcobright.seed.callflow.api.CdrEvent;
import com.telcobright.seed.callflow.internal.FileCallJournal;
import com.telcobright.seed.callflow.samples.AdFlow;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.samples.Wire;
import com.telcobright.seed.callflow.spi.CallJournal;
import com.telcobright.statewalk.pipeline.StepMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0049 R1-6 — every taka the switch reserved ends in a record or goes back, a process death included. A call that was HANDED
 * OVER has a line in the journal of the calls in the air; its own end marks it done; the next start publishes every line not done
 * as the call's record — ended {@code LOST_AT_RESTART}, every tier charged what it reserved — before the first call.
 *
 * <p>A kill is what it leaves on disk: the journal's file is copied at the moment of the "kill" and the next process starts on the
 * copy, with a ledger and a CDR road of its own. The story is the base's own: an ad view of Unilever (701) in {@code res_44}, two
 * tiers — {@code res_44} 0.50 and {@code btcl} 0.40.
 */
class CallsInTheAirTest {

    @TempDir Path dir;

    private AdFlow.View handedOver(AdFlow flow, String id) {
        AdFlow.View view = Scene.view(id, "dhaka-zone");
        view.createdAtMs = Instant.parse("2026-10-05T04:00:00Z").toEpochMilli();
        assertThat(flow.preprocess(view)).isNull();
        assertThat(flow.admit(view, StepMode.LIVE).accepted()).isTrue();
        assertThat(flow.handOver(view, () -> true)).as("handed over").isTrue();
        return view;
    }

    /** What a kill leaves: the journal's file as it is now, copied; the dead process writes nothing to the copy. */
    private Path whatAKillLeaves(Path journal) throws Exception {
        Path left = dir.resolve("after-the-kill-" + System.nanoTime() + ".jsonl");
        Files.copy(journal, left);
        return left;
    }

    @Test
    void the_calls_a_killed_process_left_in_the_air_are_published_at_the_next_start_charged_as_reserved_and_once() throws Exception {
        Scene before = new Scene();
        FileCallJournal journal = new FileCallJournal(dir.resolve("air.jsonl"));
        AdFlow first = before.withJournal(journal).ad(Scene.settings(4), true);
        handedOver(first, "air-1");                                                  // never shown
        AdFlow.View shown = handedOver(first, "air-2");
        shown.answeredAtMs = Instant.parse("2026-10-05T04:00:02Z").toEpochMilli();
        first.noteInTheAir("air-2", shown.answeredAtMs, 7);                          // the switch learned it was shown, 7 s billed
        AdFlow.View ended = handedOver(first, "air-3");
        ended.endedAtMs = Instant.parse("2026-10-05T04:00:20Z").toEpochMilli();
        ended.endCause = "NOT_SHOWN";
        first.end(ended, CallState.FAILED, Scene.NO_MACHINE);                        // its own end published its record before the kill
        assertThat(journal.inTheAir()).isEqualTo(2);
        Path left = whatAKillLeaves(dir.resolve("air.jsonl"));

        Scene after = new Scene();
        FileCallJournal reopened = new FileCallJournal(left);
        after.withJournal(reopened).ad(Scene.settings(4), true).publishWhatWasLeftInTheAir();

        assertThat(after.cdrs.published()).extracting(p -> p.callId()).as("the two calls in the air, not the one whose end was published")
            .containsExactlyInAnyOrder("air-1", "air-2");
        for (String id : List.of("air-1", "air-2")) {
            List<CdrEvent> tiers = after.cdrs.of(id).get(0).tiers();
            assertThat(tiers).extracting(c -> c.tenant).containsExactly("res_44", "btcl");
            assertThat(tiers).extracting(c -> c.inPartnerCost).as("every tier charged what it reserved").usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("0.50"), new BigDecimal("0.40"));
            assertThat(tiers).allSatisfy(c -> {
                assertThat(c.hangupCause).isEqualTo(CallCause.LOST_AT_RESTART);
                assertThat(c.callId).as("the task's id: R1-1's LOST_AT_RESTART row is the same view").isEqualTo(id);
                assertThat(c.endTime).as("the end = the last moment the switch knew of the call").isNotNull();
                assertThat(c.startTime).isNotNull();
            });
        }
        CdrEvent notShown = after.cdrs.of("air-1").get(0).tiers().get(0), wasShown = after.cdrs.of("air-2").get(0).tiers().get(0);
        assertThat(notShown.answerTime).as("no answer reached the switch").isNull();
        assertThat(notShown.durationSec).isEqualByComparingTo("0");
        assertThat(wasShown.answerTime).as("what was learned is said").isEqualTo("2026-10-05 10:00:02");
        assertThat(wasShown.durationSec).isEqualByComparingTo("7");
        assertThat(reopened.inTheAir()).as("published = done").isZero();
        assertThat(Files.size(left)).as("nothing open: the file is emptied").isZero();

        Scene third = new Scene();
        third.withJournal(new FileCallJournal(left)).ad(Scene.settings(4), true).publishWhatWasLeftInTheAir();
        assertThat(third.cdrs.published()).as("a start after that publishes nothing twice").isEmpty();
    }

    @Test
    void the_engine_publishes_them_before_its_first_call() throws Exception {
        Scene before = new Scene();
        FileCallJournal journal = new FileCallJournal(dir.resolve("engine.jsonl"));
        handedOver(before.withJournal(journal).ad(Scene.settings(4), true), "air-e1");
        Path left = whatAKillLeaves(dir.resolve("engine.jsonl"));

        Scene after = new Scene();
        try (CallFlowEngine<AdFlow.View> engine = CallFlowEngine.of(after.withJournal(new FileCallJournal(left)).ad(Scene.settings(4), true))
                .child(Wire.TYPE, Wire::new).start()) {
            assertThat(after.cdrs.of("air-e1")).as("published by the start itself, before any launch").hasSize(1);
            assertThat(engine.stats().cdrPublished()).isEqualTo(1);
        }
    }

    @Test
    void a_call_whose_line_cannot_be_written_is_not_handed_over_and_the_history_says_why() throws Exception {
        Scene scene = new Scene();
        CallJournal refusing = new CallJournal() {
            @Override public void handedOver(String callId, long atMs, String records) { throw new IllegalStateException("the disk is full (a test's)"); }
            @Override public void noted(String callId, long atMs, long answeredAtMs, double billedSec) { }
            @Override public void done(String callId) { }
            @Override public List<Leftover> leftovers() { return List.of(); }
            @Override public String where() { return "a journal that refuses"; }
        };
        AdFlow flow = scene.withJournal(refusing).ad(Scene.settings(4), true);
        AdFlow.View view = Scene.view("air-r1", "dhaka-zone");
        assertThat(flow.preprocess(view)).isNull();
        assertThat(flow.admit(view, StepMode.LIVE).accepted()).isTrue();
        boolean[] asked = {false};

        assertThat(flow.handOver(view, () -> asked[0] = true)).as("not handed over").isFalse();

        assertThat(asked[0]).as("the application's fact is not even asked: nothing may take it").isFalse();
        assertThat(view.history.snapshot().toString()).contains("could not be written").contains("the disk is full (a test's)").contains("its reserves go back");
    }

    @Test
    void a_call_that_ended_before_it_could_be_handed_over_leaves_no_open_line() throws Exception {
        Scene scene = new Scene();
        FileCallJournal journal = new FileCallJournal(dir.resolve("first.jsonl"));
        AdFlow flow = scene.withJournal(journal).ad(Scene.settings(4), true);
        AdFlow.View view = Scene.view("air-f1", "dhaka-zone");
        assertThat(flow.preprocess(view)).isNull();
        assertThat(flow.admit(view, StepMode.LIVE).accepted()).isTrue();

        assertThat(flow.handOver(view, () -> false)).as("the fact said no: its end took it first").isFalse();

        assertThat(journal.inTheAir()).isZero();
        assertThat(new FileCallJournal(whatAKillLeaves(dir.resolve("first.jsonl"))).leftovers()).as("a start after a kill: nothing to publish").isEmpty();
    }

    @Test
    void the_file_holds_the_calls_in_the_air_only() throws Exception {
        Path file = dir.resolve("roll.jsonl");
        FileCallJournal journal = new FileCallJournal(file, 2_000);
        String records = "[{\"callId\":\"x\",\"pad\":\"" + "p".repeat(300) + "\"}]";
        for (int i = 1; i <= 6; i++) journal.handedOver("r-" + i, i, records);
        journal.noted("r-6", 7, 5, 1.5);
        for (int i = 1; i <= 5; i++) journal.done("r-" + i);

        assertThat(Files.size(file)).as("past 2,000 bytes it is written again with its open lines: it never stays above the bound").isLessThan(2_000);
        List<CallJournal.Leftover> left = new FileCallJournal(file).leftovers();
        assertThat(left).extracting(CallJournal.Leftover::callId).containsExactly("r-6");
        assertThat(left.get(0).answeredAtMs()).isEqualTo(5);
        assertThat(left.get(0).billedSec()).isEqualTo(1.5);

        journal.done("r-6");
        assertThat(Files.size(file)).as("nothing open: emptied").isZero();
    }

    @Test
    void a_half_line_at_the_end_is_skipped_and_the_whole_lines_are_read() throws Exception {
        Path file = dir.resolve("cut.jsonl");
        FileCallJournal journal = new FileCallJournal(file);
        journal.handedOver("c-1", 1, "[{\"callId\":\"c-1\"}]");
        journal.close();
        Files.writeString(file, "{\"k\":\"v\",\"id\":\"c-2\",\"at\":2,\"r\":[{\"cal", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertThat(new FileCallJournal(file).leftovers()).extracting(CallJournal.Leftover::callId).containsExactly("c-1");
    }

    /** The cost of the line on the view's thread: the records made and written, then marked done. */
    @Test
    void the_line_costs_a_call_little() throws Exception {
        Scene scene = new Scene();
        FileCallJournal journal = new FileCallJournal(dir.resolve("cost.jsonl"));
        AdFlow flow = scene.withJournal(journal).ad(Scene.settings(4), true);
        scene.ledger.fund("res_44", 701, "100000.00").fund("btcl", 44, "100000.00");
        int warm = 2_000, n = 2_000;
        List<AdFlow.View> views = new java.util.ArrayList<>();
        for (int i = 0; i < warm + n; i++) {
            AdFlow.View view = Scene.view("cost-" + i, "dhaka-zone");
            assertThat(flow.preprocess(view)).isNull();
            assertThat(flow.admit(view, StepMode.LIVE).accepted()).isTrue();
            views.add(view);
        }
        for (AdFlow.View view : views.subList(0, warm)) { flow.handOver(view, () -> true); journal.done(view.sessionKey); }   // the JIT's warm-up
        List<AdFlow.View> measured = views.subList(warm, warm + n);
        long started = System.nanoTime();
        for (AdFlow.View view : measured) flow.handOver(view, () -> true);
        long handOverNs = System.nanoTime() - started;
        started = System.nanoTime();
        for (AdFlow.View view : measured) journal.done(view.sessionKey);
        long doneNs = System.nanoTime() - started;

        System.out.printf("R1-6 cost: the hand-over line %.1f µs a call (records made + one append), the done line %.1f µs a call, over %d calls%n",
            handOverNs / 1_000.0 / n, doneNs / 1_000.0 / n, n);
        assertThat(handOverNs / n).as("a hand-over line takes well under a millisecond").isLessThan(1_000_000);
        assertThat(journal.inTheAir()).isZero();
    }
}
