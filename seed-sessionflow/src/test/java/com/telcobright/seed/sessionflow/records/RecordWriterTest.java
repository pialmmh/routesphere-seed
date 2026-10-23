package com.telcobright.seed.sessionflow.records;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.RecordStatement;
import com.telcobright.seed.sessionflow.dependencies.RecordDialect;
import com.telcobright.seed.sessionflow.internal.BatchLock;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import com.telcobright.seed.sessionflow.spi.SummaryPing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §B · The writer — the base's part: ONE platform thread per root, batches of N records or T, ONE transaction per batch under billing-core's
 * per-tier lock, ONE {@code summary_affected} row per tier of the records written now, then — after the commit — the pings on their own
 * thread and the journal's lines done. C3: 500 sessions ending at once.
 */
class RecordWriterTest {

    private RecordsLab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    /** The journal of the sessions in the air, as the writer sees it: each done, and whether its records were committed when it came. */
    static final class WatchingJournal implements SessionJournal {
        final List<String> done = new CopyOnWriteArrayList<>();
        volatile RecordsLab lab;

        @Override public void done(String callId) {
            try {
                done.add(callId + (lab.count("select count(*) from btcl.cdr where ChannelCallUuid = '" + callId + "'") > 0 ? ":committed" : ":NOT committed"));
            } catch (Exception e) {
                done.add(callId + ":" + e);
            }
        }
        @Override public void handedOver(String callId, long atMs, String records) { }
        @Override public void noted(String callId, long atMs, long answeredAtMs, double billedSec) { }
        @Override public List<Leftover> leftovers() { return List.of(); }
        @Override public String where() { return "the watching journal"; }
    }

    /** A ping port that writes down each ping and the thread it came on. */
    static final class Pings implements SummaryPing {
        final List<String> heard = new CopyOnWriteArrayList<>();
        @Override public void ping(String tenant, String entity, int rows) { heard.add(tenant + ":" + entity + ":" + rows + "@" + Thread.currentThread().getName()); }
    }

    @Test
    void fiveHundredSessionsEndingAtOnce_oneWriterThread_everyRecordExactlyOnce_noDeadlock_aFewBatches_C3() throws Exception {
        lab = new RecordsLab(RecordDialect.POSTGRESQL, SessionJournal.NONE, b -> b.batch(200, Duration.ofMillis(50)));
        CountDownLatch go = new CountDownLatch(1);
        int[] perTier = new int[4];
        try (ExecutorService ending = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 500; i++) {
                int tiers = 1 + i % 3;
                perTier[tiers]++;
                String id = "s-" + i;
                ending.submit(() -> { go.await(); lab.flow.write(RecordsLab.session(id, tiers), null); return null; });
            }
            go.countDown();
        }
        assertThat(lab.awaitWritten()).as("no deadlock: everything committed").isTrue();

        assertThat(lab.count("select count(*) from btcl.cdr")).isEqualTo(500);
        assertThat(lab.count("select count(*) from res_44.cdr")).isEqualTo(perTier[2] + perTier[3]);
        assertThat(lab.count("select count(*) from res_44_7.cdr")).isEqualTo(perTier[3]);
        for (String tier : List.of("btcl", "res_44", "res_44_7")) {
            assertThat(lab.count("select count(*) from (select ChannelCallUuid from " + tier + ".cdr group by ChannelCallUuid having count(*) > 1) d"))
                .as("no record twice in " + tier).isZero();
            int summed = lab.summaryRowsOf(tier).stream().mapToInt(List::size).sum();
            assertThat(summed).as("the summary input of " + tier + " holds each record once").isEqualTo(lab.count("select count(*) from " + tier + ".cdr"));
        }
        long batches = lab.flow.kit().counters().recordBatches.get();
        assertThat(batches).as("1000 records in batches of 200").isBetween(5L, 60L);
        assertThat(lab.calls().stream().filter(c -> c.startsWith("sql ")).map(c -> c.substring(c.indexOf('@') + 1)).distinct().toList())
            .as("one writer thread did every statement").containsExactly("records-btcl");
        assertThat(lab.longs("select count(*) from btcl.summary_affected").get(0)).isEqualTo(batches);
    }

    @Test
    void theBatchTakesBillingCoresLockOfEveryTier_inNameOrder_beforeItsFirstStatement_andLetsItGoAfterTheCommit_MySql() throws Exception {
        lab = new RecordsLab(RecordDialect.MYSQL, SessionJournal.NONE, b -> b.batch(3, Duration.ofSeconds(5)));
        lab.flow.write(RecordsLab.session("m-1", 3), null);
        assertThat(lab.awaitWritten()).isTrue();

        List<String> batch = lab.calls().stream().map(c -> c.contains(" @") ? c.substring(0, c.indexOf(" @")) : c)
            .filter(c -> !c.startsWith("sql select get_lock") && !c.startsWith("sql select release_lock")).toList();
        int begin = batch.indexOf("begin"), commit = batch.indexOf("commit");
        assertThat(batch.subList(begin + 1, begin + 4)).as("the three tiers' locks, in name order, first")
            .containsExactly("lock billing_batch_btcl", "lock billing_batch_res_44", "lock billing_batch_res_44_7");
        assertThat(batch.get(begin + 4)).as("then the first statement").startsWith("sql select 1 from");
        assertThat(batch.subList(commit + 1, commit + 4)).as("let go after the commit")
            .containsExactly("unlock billing_batch_btcl", "unlock billing_batch_res_44", "unlock billing_batch_res_44_7");
        assertThat(batch.subList(begin, commit)).noneMatch(c -> c.startsWith("unlock"));
    }

    @Test
    void onPostgres_theLockIsTheAdvisoryKeyBillingCoreMakesOfTheSameName_takenBeforeTheFirstStatement() throws Exception {
        lab = new RecordsLab(RecordDialect.POSTGRESQL, SessionJournal.NONE, b -> b.batch(1, Duration.ofSeconds(5)));
        lab.flow.write(RecordsLab.session("p-1", 1), null);
        assertThat(lab.awaitWritten()).isTrue();

        List<String> calls = lab.calls().stream().map(c -> c.contains(" @") ? c.substring(0, c.indexOf(" @")) : c)
            .filter(c -> !c.startsWith("sql select pg_try")).toList();
        int begin = calls.indexOf("begin");
        assertThat(calls.get(begin + 1)).isEqualTo("xlock " + billingCoresKeyOf("billing_batch_btcl"));
        assertThat(calls.get(begin + 2)).startsWith("sql select 1 from btcl.cdr");
        assertThat(BatchLock.keyOf("billing_batch_btcl")).isEqualTo(billingCoresKeyOf("billing_batch_btcl"));
    }

    @Test
    void oneSummaryRowPerTierOfABatch_holdingOnlyTheRecordsWrittenNow_andNoneForATierWithNone() throws Exception {
        lab = new RecordsLab(RecordDialect.POSTGRESQL, SessionJournal.NONE, b -> b.batch(2, Duration.ofSeconds(5)));
        lab.flow.write(RecordsLab.session("o-1", 2), null);
        assertThat(lab.awaitWritten()).isTrue();
        lab.flow.write(RecordsLab.session("o-1", 2), null);                               // the same session again: both tiers written before
        assertThat(lab.awaitWritten()).isTrue();
        List<CdrEvent> mixed = new ArrayList<>(RecordsLab.session("o-2", 1));
        mixed.addAll(RecordsLab.session("o-1", 1));                                    // a new one and a replay in ONE batch
        lab.flow.write(mixed.subList(0, 1), null);
        lab.flow.write(mixed.subList(1, 2), null);
        assertThat(lab.awaitWritten()).isTrue();

        assertThat(lab.count("select count(*) from btcl.cdr")).isEqualTo(2);
        assertThat(lab.summaryRowsOf("btcl")).extracting(row -> row.stream().map(e -> e.cdr().inPartnerId() + "/" + e.cdr().startTime()).toList())
            .as("one row per batch that wrote, only what it wrote").hasSize(2).allMatch(row -> row.size() == 1);
        assertThat(lab.summaryRowsOf("res_44")).as("the replay batch wrote no row in res_44").hasSize(1);
        assertThat(lab.flow.kit().counters().recordsWrittenBefore.get()).isEqualTo(3);
    }

    @Test
    void afterTheCommit_thePingsGoOnTheirOwnThread_oneForEachTierWithANewRow_andEachSessionsJournalLineIsDone() throws Exception {
        WatchingJournal journal = new WatchingJournal();
        Pings pings = new Pings();
        lab = new RecordsLab(RecordDialect.POSTGRESQL, journal, b -> b.batch(5, Duration.ofSeconds(5)).ping(pings));
        journal.lab = lab;
        lab.flow.write(RecordsLab.session("j-1", 3), null);
        lab.flow.write(RecordsLab.session("j-2", 2), null);
        assertThat(lab.awaitWritten()).isTrue();

        assertThat(journal.done).containsExactly("j-1:committed", "j-2:committed");
        long until = System.currentTimeMillis() + 5_000;
        while (pings.heard.size() < 3 && System.currentTimeMillis() < until) Thread.sleep(10);
        assertThat(pings.heard).containsExactlyInAnyOrder("btcl:cdr:2@records-summary-ping", "res_44:cdr:2@records-summary-ping",
            "res_44_7:cdr:1@records-summary-ping");
    }

    @Test
    void aBatchThatDoesNotCommit_rollsBackWhole_andIsTriedAgainUntilItCommits() throws Exception {
        lab = new RecordsLab(RecordDialect.POSTGRESQL, SessionJournal.NONE, b -> b.batch(2, Duration.ofSeconds(5)).retries(Duration.ofMillis(50), Duration.ofMillis(200)));
        lab.flow.sdrRows = sdr -> List.of(RecordStatement.of("insert into btcl.lane_sdr (id) values (?)", sdr));
        lab.flow.write(RecordsLab.session("r-1", 2), "r-1");                          // its SDR's table is not there yet: the batch fails
        Thread.sleep(400);
        assertThat(lab.count("select count(*) from btcl.cdr") + lab.count("select count(*) from res_44.cdr")).as("rolled back whole").isZero();
        assertThat(lab.count("select count(*) from btcl.summary_affected")).isZero();

        try (var c = lab.pool.getConnection()) { c.createStatement().execute("create table btcl.lane_sdr (id varchar primary key)"); }
        assertThat(lab.awaitWritten()).as("tried again until it commits").isTrue();
        assertThat(lab.count("select count(*) from btcl.lane_sdr")).as("the SDR in the same transaction").isEqualTo(1);
        assertThat(lab.count("select count(*) from res_44.cdr")).isEqualTo(1);
        assertThat(lab.calls().stream().filter("rollback"::equals).count()).isGreaterThanOrEqualTo(2);
    }

    /** billing-core's PostgresEdge.LockKeyOf, written again here (it is not on this classpath). */
    private static long billingCoresKeyOf(String name) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
    }
}
