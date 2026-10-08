package com.telcobright.seed.campaign.writer;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.seed.campaign.spi.CampaignStore;
import com.telcobright.seed.campaign.spi.StoreChange;
import com.telcobright.seed.campaign.spi.StoreRepair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ad-sphere ARCH-0043 R1-1 — a store behind one writer and a queue: what the switch knows about a task reaches its store, late if it
 * must, never not at all; and a task never waits for the store. The store here is a fake that can be away, be slow, refuse one change
 * by its own rules, and remembers every batch it took.
 */
@org.junit.jupiter.api.Timeout(60)
class QueuedCampaignStoreTest {

    static final Instant T0 = Instant.parse("2026-10-04T06:00:00Z");

    /** A store that remembers what it took, batch by batch, and can be made to fail. */
    static final class FakeStore implements CampaignStore {
        final List<List<StoreChange>> batches = new CopyOnWriteArrayList<>();
        final Map<String, CampaignTask> rows = new ConcurrentHashMap<>();
        final Map<Integer, int[]> counters = new ConcurrentHashMap<>();
        final List<String> order = new CopyOnWriteArrayList<>();
        final List<String> repairs = new CopyOnWriteArrayList<>();
        volatile boolean away;
        volatile CountDownLatch hold;
        volatile int asked;

        @Override
        public void write(List<StoreChange> batch) {
            asked++;
            CountDownLatch gate = hold;
            if (gate != null) { try { gate.await(8, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw away(); } }
            if (away) throw away();
            for (StoreChange change : batch) {
                if (change instanceof StoreChange.TaskInserted c && c.task().uniqueId().startsWith("bad")) {
                    throw new IllegalStateException("task id '" + c.task().uniqueId() + "' is not one this store takes");      // its own rule: all of the batch or none
                }
            }
            for (StoreChange change : batch) {
                switch (change) {
                    case StoreChange.TaskInserted c -> { rows.putIfAbsent(c.task().uniqueId(), c.task()); order.add("insert " + c.task().uniqueId()); }
                    case StoreChange.TaskUpdated c -> { rows.put(c.task().uniqueId(), c.task()); order.add("update " + c.task().uniqueId() + " " + c.task().state()); }
                    case StoreChange.CountersBumped c -> {
                        int[] k = counters.computeIfAbsent(c.campaignId(), id -> new int[3]);
                        synchronized (k) { k[0] += c.sent(); k[1] += c.failed(); k[2] += c.pending(); }
                    }
                    case StoreChange.CampaignCompleted c -> order.add("complete " + c.campaignId());
                }
            }
            batches.add(List.copyOf(batch));
        }

        /** As a JDBC store says "the database does not answer": the cause is the pool's own exception. */
        static IllegalStateException away() {
            return new IllegalStateException("a batch could not be written: switch-wroot - Connection is not available, request timed out after 5001ms",
                new SQLTransientConnectionException("switch-wroot - Connection is not available, request timed out after 5001ms"));
        }

        @Override public List<Campaign> campaigns(String tenantId) { return List.of(); }
        @Override public void insertTask(CampaignTask task) { write(List.of(new StoreChange.TaskInserted(task))); }
        @Override public void updateTask(CampaignTask task) { write(List.of(new StoreChange.TaskUpdated(task))); }
        @Override public void bumpCounters(String t, int c, int s, int f, int p) { write(List.of(new StoreChange.CountersBumped(t, c, s, f, p))); }
        @Override public void markComplete(String t, int c) { write(List.of(new StoreChange.CampaignCompleted(t, c))); }

        @Override
        public StoreRepair repairAfterRestart(String tenantName, String cause, Instant at) {
            repairs.add(tenantName + " " + cause);
            order.add("repair " + tenantName);
            return new StoreRepair(3, 1, "3 task(s) closed; 1 campaign set");
        }
    }

    @TempDir Path dir;
    final FakeStore store = new FakeStore();
    QueuedCampaignStore queued;
    final PrintStream realErr = System.err;

    @AfterEach
    void stop() {
        System.setErr(realErr);
        store.away = false;
        if (store.hold != null) store.hold.countDown();
        if (queued != null) queued.close();
    }

    static WriterSettings quick() { return WriterSettings.standard().withRetry(20, 80).withStopWait(600).withStartWait(600); }

    QueuedCampaignStore open(WriterSettings settings) {
        queued = QueuedCampaignStore.open("wroot", store, settings, dir, null);
        return queued;
    }

    static CampaignTask task(String id) {
        return new CampaignTask(id, "wroot", 7, 55, CampaignKind.AD, "aa:bb:cc:dd:ee:01", "walton-15", "dhaka-01", "site-7", "wifi-1", TaskState.PROCESSING,
            T0, null, null, 0, null, null, Map.of("viewSeconds", 15, "facts", Map.of("zone", "dhaka-01")));
    }

    /** One view's life as the service tells it: claimed (the row, pending +1), then completed (the row, sent +1, pending -1). */
    void aViewIsClaimed(CampaignStore s, String id) { s.insertTask(task(id)); s.bumpCounters("wroot", 7, 0, 0, +1); }

    void aViewEnds(CampaignStore s, String id) {
        s.updateTask(task(id).completed(T0.plusSeconds(15), 15, new TaskCharge(1001L, "BDT", BigDecimal.ZERO, new BigDecimal("0.30"), "1001"), "CREDITED"));
        s.bumpCounters("wroot", 7, +1, 0, -1);
    }

    static void await(String what, BooleanSupplier done) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > until) throw new AssertionError("timed out waiting for: " + what);
            Thread.sleep(10);
        }
    }

    List<String> journalLines() throws Exception {
        Path file = dir.resolve("campaign-store-wroot.jsonl");
        return Files.exists(file) ? Files.readAllLines(file) : List.of();
    }

    // ── telling never waits; one writer, batches ─────────────────────────────

    @Test
    void two_thousand_views_that_end_at_once_never_wait_for_the_store_and_every_end_reaches_it_in_batches() throws Exception {
        open(quick());
        for (int i = 0; i < 2000; i++) aViewIsClaimed(queued, "v" + i);
        assertThat(queued.awaitWritten(10_000)).isTrue();
        store.hold = new CountDownLatch(1);                                     // the store answers nothing for now

        long asked = System.nanoTime();
        Thread[] ends = new Thread[32];
        for (int t = 0; t < ends.length; t++) {
            int mine = t;
            ends[t] = new Thread(() -> { for (int i = mine; i < 2000; i += 32) aViewEnds(queued, "v" + i); });
            ends[t].start();
        }
        for (Thread end : ends) end.join();
        long tellingMs = (System.nanoTime() - asked) / 1_000_000;

        assertThat(tellingMs).as("2,000 ends were told while the store answered NOTHING: no view waited for it").isLessThan(2_000);
        assertThat(store.rows.values()).as("…and none of them is in the store yet").allSatisfy(row -> assertThat(row.state()).isEqualTo(TaskState.PROCESSING));
        store.hold.countDown();
        store.hold = null;

        assertThat(queued.awaitWritten(10_000)).isTrue();
        assertThat(store.rows).hasSize(2000);
        assertThat(store.rows.values()).as("every task row is final").allSatisfy(row -> assertThat(row.state()).isEqualTo(TaskState.SENT));
        assertThat(store.counters.get(7)).as("sent 2,000, failed 0, pending 0").containsExactly(2000, 0, 0);
        assertThat(store.batches).as("in batches of at most 500 changes, not one write a change").allSatisfy(b -> assertThat(b.size()).isLessThanOrEqualTo(500));
        assertThat(store.batches.size()).isLessThan(200);
        assertThat(queued.stats().waiting()).isZero();
    }

    @Test
    void the_order_of_the_telling_is_the_order_of_the_writing_a_tasks_insert_comes_before_its_update() throws Exception {
        open(quick().withBatch(7));
        for (int i = 0; i < 200; i++) { aViewIsClaimed(queued, "v" + i); aViewEnds(queued, "v" + i); }
        assertThat(queued.awaitWritten(10_000)).isTrue();

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 200; i++) { expected.add("insert v" + i); expected.add("update v" + i + " SENT"); }
        assertThat(store.order).containsExactlyElementsOf(expected);
    }

    // ── a failed batch is written again ──────────────────────────────────────

    @Test
    void while_the_store_is_away_nothing_is_lost_and_it_is_one_error_and_one_info_not_a_line_a_task() throws Exception {
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        System.setErr(new PrintStream(said, true, StandardCharsets.UTF_8));
        open(quick());
        store.away = true;

        for (int i = 0; i < 300; i++) { aViewIsClaimed(queued, "v" + i); aViewEnds(queued, "v" + i); }
        await("the writer tried, and tried again", () -> store.asked >= 4);
        assertThat(store.rows).as("the store took nothing").isEmpty();
        assertThat(queued.stats().failing()).isTrue();
        assertThat(queued.stats().lastFailure()).contains("Connection is not available");
        store.away = false;                                                     // it comes back

        assertThat(queued.awaitWritten(10_000)).isTrue();
        assertThat(store.rows).hasSize(300);
        assertThat(store.rows.values()).allSatisfy(row -> assertThat(row.state()).isEqualTo(TaskState.SENT));
        assertThat(store.counters.get(7)).as("each bump counted once: a batch that failed wrote nothing").containsExactly(300, 0, 0);
        assertThat(queued.stats().failing()).isFalse();
        System.setErr(realErr);
        List<String> lines = said.toString(StandardCharsets.UTF_8).lines().filter(l -> l.contains("campaign store wroot")).toList();
        assertThat(lines.stream().filter(l -> l.contains("ERROR"))).as("ONE error when it started failing").singleElement()
            .satisfies(l -> assertThat(l).contains("could not be written").contains("Connection is not available").contains("no task waits"));
        assertThat(lines.stream().filter(l -> l.contains("takes writes again"))).as("ONE line when it wrote again").hasSize(1);
    }

    @Test
    void a_change_the_store_refuses_by_its_own_rules_is_put_aside_and_does_not_hold_the_others() throws Exception {
        open(quick());
        aViewIsClaimed(queued, "v1");
        aViewIsClaimed(queued, "bad-id");                                        // the store will never take this one
        aViewIsClaimed(queued, "v2");
        aViewEnds(queued, "v1");
        aViewEnds(queued, "v2");

        assertThat(queued.awaitWritten(10_000)).as("the refused change is settled too: put aside, with why").isTrue();
        assertThat(store.rows.keySet()).containsExactlyInAnyOrder("v1", "v2");
        assertThat(store.rows.get("v2").state()).isEqualTo(TaskState.SENT);
        assertThat(queued.stats().rejected()).isEqualTo(1);
        List<String> rejected = Files.readAllLines(dir.resolve("campaign-store-wroot.rejected.jsonl"));
        assertThat(rejected).singleElement().satisfies(line -> assertThat(line).contains("\"kind\":\"insert\"").contains("bad-id").contains("is not one this store takes"));
        assertThat(journalLines()).as("nothing waits on disk").isEmpty();
    }

    @Test
    void a_store_that_is_away_is_never_taken_for_a_change_it_refuses() {
        assertThat(QueuedCampaignStore.refusedByTheStoresOwnRules(FakeStore.away())).as("the pool has no connection").isFalse();
        assertThat(QueuedCampaignStore.refusedByTheStoresOwnRules(new IllegalStateException("x", new SQLException("the connection is closed", "08006")))).isFalse();
        assertThat(QueuedCampaignStore.refusedByTheStoresOwnRules(new IllegalStateException("x", new SQLException("deadlock detected", "40P01")))).isFalse();
        assertThat(QueuedCampaignStore.refusedByTheStoresOwnRules(new IllegalStateException("x", new SQLException("relation campaign_task does not exist", "42P01"))))
            .as("a table that is not there yet is a deployment that is not ready: waited out, nothing is put aside").isFalse();
        assertThat(QueuedCampaignStore.refusedByTheStoresOwnRules(new IllegalStateException("x", new SQLException("duplicate key", "23505")))).as("a key").isTrue();
        assertThat(QueuedCampaignStore.refusedByTheStoresOwnRules(new IllegalStateException("x", new SQLException("value too long", "22001")))).as("a width").isTrue();
        assertThat(QueuedCampaignStore.refusedByTheStoresOwnRules(new IllegalStateException("task id 'x' is 60 characters"))).as("the store's own check, before any SQL").isTrue();
    }

    // ── the queue is bounded: a line on disk instead of a wait ───────────────

    @Test
    void when_the_queue_is_full_a_change_is_a_line_of_the_journal_and_the_writer_writes_it_when_it_has_caught_up() throws Exception {
        open(quick().withQueue(20).withBatch(5));
        store.hold = new CountDownLatch(1);

        long asked = System.nanoTime();
        for (int i = 0; i < 100; i++) { aViewIsClaimed(queued, "v" + i); aViewEnds(queued, "v" + i); }      // 400 changes, the queue holds 20
        long tellingMs = (System.nanoTime() - asked) / 1_000_000;

        assertThat(tellingMs).as("no change waited for room in the queue").isLessThan(2_000);
        assertThat(queued.stats().onDisk()).as("what the queue could not hold is in the journal only, one line a change").isGreaterThanOrEqualTo(370);
        assertThat(journalLines()).as("F10: EVERY change is a line of the journal, the queued ones too").hasSize(400);
        store.hold.countDown();
        store.hold = null;

        assertThat(queued.awaitWritten(10_000)).isTrue();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 100; i++) { expected.add("insert v" + i); expected.add("update v" + i + " SENT"); }
        assertThat(store.order).as("the queue first, then the lines, then the queue again: the order of the telling").containsExactlyElementsOf(expected);
        assertThat(store.counters.get(7)).containsExactly(100, 0, 0);
        assertThat(journalLines()).as("the journal is empty again").isEmpty();
        assertThat(queued.stats().waiting()).isZero();

        aViewIsClaimed(queued, "after");                                         // and changes wait in the queue again
        assertThat(queued.awaitWritten(5_000)).isTrue();
        assertThat(journalLines()).isEmpty();
        assertThat(store.rows).containsKey("after");
    }

    // ── a reader of the counts sees what was told before it asked ────────────

    @Test
    void a_read_of_the_campaigns_waits_for_what_was_told_before_it_and_is_refused_by_name_when_the_store_does_not_take_it() throws Exception {
        open(quick().withRetry(20, 2_000));                                      // a read waits at most the retry's cap
        store.hold = new CountDownLatch(1);
        aViewIsClaimed(queued, "v1");
        aViewEnds(queued, "v1");
        CountDownLatch release = store.hold;
        new Thread(() -> { try { Thread.sleep(150); } catch (InterruptedException ignored) { } release.countDown(); }).start();

        queued.campaigns("wroot");                                               // returns only when v1's end is in the store

        assertThat(store.rows.get("v1").state()).as("the read came after the write").isEqualTo(TaskState.SENT);
        assertThat(store.counters.get(7)).containsExactly(1, 0, 0);

        store.hold = null;
        store.away = true;
        aViewIsClaimed(queued, "v2");
        assertThatThrownBy(() -> queued.campaigns("wroot")).as("a count that lags behind what was told is not handed out")
            .hasMessageContaining("the campaigns of wroot cannot be read now").hasMessageContaining("2 change(s) of campaign store wroot are not in the store yet")
            .hasMessageContaining("Connection is not available");
    }

    // ── a clean stop, and the next start ─────────────────────────────────────

    @Test
    void a_stop_drains_the_queue_and_leaves_no_journal() throws Exception {
        open(quick());
        for (int i = 0; i < 500; i++) { aViewIsClaimed(queued, "v" + i); aViewEnds(queued, "v" + i); }

        queued.close();

        assertThat(store.rows).hasSize(500);
        assertThat(store.counters.get(7)).containsExactly(500, 0, 0);
        assertThat(journalLines()).isEmpty();
    }

    @Test
    void what_a_stop_could_not_write_is_in_the_journal_in_its_order_and_the_next_start_writes_it_before_anything_else() throws Exception {
        open(quick().withQueue(10).withBatch(4));
        store.away = true;
        for (int i = 0; i < 20; i++) { aViewIsClaimed(queued, "v" + i); aViewEnds(queued, "v" + i); }       // 80 changes: 10 queued (4 of them in the writer's hand), the rest on disk

        queued.close();                                                          // the store is still away when the stop's wait is over

        assertThat(store.rows).isEmpty();
        List<String> kept = journalLines();
        assertThat(kept).as("every change is a line: what the writer held, what the queue held, then what was on disk already").hasSize(80);
        assertThat(kept.get(0)).contains("\"kind\":\"insert\"").contains("\"uniqueId\":\"v0\"");
        assertThat(kept.get(1)).contains("\"kind\":\"bump\"").contains("\"pending\":1");
        assertThat(kept.get(2)).contains("\"kind\":\"update\"").contains("\"uniqueId\":\"v0\"").contains("\"state\":\"SENT\"").contains("\"cost\":0.30");
        assertThat(kept.get(79)).contains("\"kind\":\"bump\"").contains("\"sent\":1");

        store.away = false;                                                      // the next process
        queued = QueuedCampaignStore.open("wroot", store, quick(), dir, "wroot");

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) { expected.add("insert v" + i); expected.add("update v" + i + " SENT"); }
        expected.add("repair wroot");
        assertThat(store.order).as("written at the start — before the writer takes a new change, and BEFORE the repair: a view that ended is not closed as lost")
            .containsExactlyElementsOf(expected);
        assertThat(store.counters.get(7)).containsExactly(20, 0, 0);
        assertThat(store.rows.get("v19").charge().cost()).as("a task comes back from its line whole").isEqualByComparingTo("0.30");
        assertThat(store.rows.get("v19").detail()).containsEntry("viewSeconds", 15).containsEntry("facts", Map.of("zone", "dhaka-01"));
        assertThat(journalLines()).isEmpty();
        assertThat(store.repairs).as("then the start repairs the store: once, for this store's tenant").containsExactly("wroot LOST_AT_RESTART");
    }

    @Test
    void a_start_whose_store_does_not_take_what_was_left_fails_by_name_and_keeps_the_journal() throws Exception {
        open(quick().withQueue(10));
        store.away = true;
        for (int i = 0; i < 5; i++) aViewIsClaimed(queued, "v" + i);
        queued.close();
        List<String> kept = journalLines();
        assertThat(kept).hasSize(10);

        assertThatThrownBy(() -> QueuedCampaignStore.open("wroot", store, quick(), dir, "wroot"))
            .hasMessageContaining("campaign store wroot").hasMessageContaining("what a stopped process left").hasMessageContaining("could not be written within 600 ms")
            .hasMessageContaining("Connection is not available");
        assertThat(store.repairs).as("no repair before the journal is in the store").isEmpty();

        store.away = false;
        queued = QueuedCampaignStore.open("wroot", store, quick(), dir, null);
        assertThat(store.rows).as("the journal was kept: the next start has it").hasSize(5);
        assertThat(store.repairs).as("a store more than one process writes is not repaired").isEmpty();
    }

    // ── F10 · a kill: the queue dies with the process, the journal does not ──

    /**
     * ARCH-0065 F10 (R-2 S3 (c): 12 served views closed LOST_AT_RESTART and 15 rows never written — the queue's last batch died with the
     * process). Every change is a line of the journal BEFORE it is queued; a {@code kill -9} with 100 changes in the queue (the store
     * answering nothing) leaves them in the file; the next start writes every one, in the order of the telling, before the first task and
     * before the repair; the file is empty after. The kill is what it leaves on disk: the journal's folder copied as it is, the dead
     * process never closed.
     */
    @Test
    void a_kill_with_a_hundred_queued_changes_loses_none_the_next_start_writes_every_row() throws Exception {
        open(quick().withBatch(10));
        for (int i = 0; i < 20; i++) { aViewIsClaimed(queued, "v" + i); aViewEnds(queued, "v" + i); }    // 80 changes written: the mark moves
        assertThat(queued.awaitWritten(10_000)).isTrue();
        store.hold = new CountDownLatch(1);                                                              // the store answers nothing from now on
        for (int i = 20; i < 70; i++) { aViewIsClaimed(queued, "v" + i); aViewEnds(queued, "v" + i); }   // 200 more: in the queue (and the file), none in the store
        await("the writer holds a batch and the queue the rest", () -> queued.stats().queued() >= 100);
        assertThat(journalLines()).as("the 80 written emptied the file; every change told since is a line").hasSize(200);
        assertThat(Files.exists(dir.resolve("campaign-store-wroot.applied"))).as("no mark: nothing of the file is in the store").isFalse();

        Path afterTheKill = dir.resolve("after-the-kill");                                               // the kill: the files as they are; the process is gone
        Files.createDirectories(afterTheKill);
        for (String f : List.of("campaign-store-wroot.jsonl", "campaign-store-wroot.applied")) if (Files.exists(dir.resolve(f))) Files.copy(dir.resolve(f), afterTheKill.resolve(f));
        FakeStore nextStore = new FakeStore();
        for (int i = 0; i < 20; i++) nextStore.rows.put("v" + i, store.rows.get("v" + i));              // the rows the dead process had written

        QueuedCampaignStore next = QueuedCampaignStore.open("wroot", nextStore, quick(), afterTheKill, "wroot");
        try {
            List<String> expected = new ArrayList<>();
            for (int i = 20; i < 70; i++) { expected.add("insert v" + i); expected.add("update v" + i + " SENT"); }
            expected.add("repair wroot");
            assertThat(nextStore.order).as("the 200 changes after the mark, in the order of the telling, before the repair").containsExactlyElementsOf(expected);
            assertThat(nextStore.rows).hasSize(70);
            assertThat(nextStore.rows.values()).as("every served view is final: none closed as lost").allSatisfy(row -> assertThat(row.state()).isEqualTo(TaskState.SENT));
            assertThat(nextStore.counters.get(7)).as("the counters say what the dead process told").containsExactly(50, 0, 0);
            assertThat(Files.exists(afterTheKill.resolve("campaign-store-wroot.jsonl"))).as("the file is emptied once everything is in").isFalse();
            assertThat(Files.exists(afterTheKill.resolve("campaign-store-wroot.applied"))).isFalse();
            aViewIsClaimed(next, "after-the-start");
            assertThat(next.awaitWritten(5_000)).isTrue();
            assertThat(nextStore.rows).containsKey("after-the-start");
        } finally {
            store.hold.countDown();
            store.hold = null;
            next.close();
        }
    }

    /** The telling's cost with the line (F10): one append a change on the caller's thread — still no wait for the store. */
    @Test
    void the_line_costs_a_telling_little() throws Exception {
        open(quick());
        for (int i = 0; i < 500; i++) aViewIsClaimed(queued, "w" + i);                                   // the JIT's warm-up
        assertThat(queued.awaitWritten(10_000)).isTrue();
        long started = System.nanoTime();
        for (int i = 0; i < 2000; i++) aViewIsClaimed(queued, "c" + i);
        long perTellingUs = (System.nanoTime() - started) / 1_000 / 4000;
        System.out.printf("F10 cost: %d µs a telling (one line appended, one offer), over 4,000 tellings%n", perTellingUs);
        assertThat(perTellingUs).as("a telling with its line takes well under a millisecond").isLessThan(1_000);
        assertThat(queued.awaitWritten(10_000)).isTrue();
    }

    // ── the journal's line ───────────────────────────────────────────────────

    @Test
    void a_change_is_one_line_and_comes_back_as_it_was() {
        ChangeCodec codec = new ChangeCodec();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("viewSeconds", 15);
        detail.put("facts", Map.of("zone", "dhaka-01", "app", "wifi"));
        detail.put("note", "a \"quoted\" word\nand a line");
        CampaignTask ended = new CampaignTask("ad-wroot-aabbcc-1", "wroot", 7, 55, CampaignKind.AD, "aa:bb", "walton-15", "dhaka-01", null, "wifi-1", TaskState.FAILED,
            T0, T0.plusMillis(1500), T0.plusSeconds(9), 7, "STALLED", new TaskCharge(1001L, "AD_view", new BigDecimal("1"), new BigDecimal("0.00"), "1001"), detail);

        for (StoreChange change : List.of(new StoreChange.TaskInserted(task("v1")), new StoreChange.TaskUpdated(ended),
                new StoreChange.CountersBumped("wroot", 7, 0, 1, -1), new StoreChange.CampaignCompleted("wroot", 7))) {
            String line = codec.write(change);
            assertThat(line).as("one line").doesNotContain("\n");
            assertThat(codec.read(line)).isEqualTo(change);
        }
        assertThatThrownBy(() -> codec.read("{\"kind\":\"other\"}")).hasMessageContaining("no kind 'other'");
        assertThatThrownBy(() -> codec.read("not json")).hasMessageContaining("not JSON");
    }
}
