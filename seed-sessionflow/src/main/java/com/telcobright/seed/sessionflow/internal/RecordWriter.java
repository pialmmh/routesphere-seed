package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.dependencies.RecordStore;
import com.telcobright.seed.sessionflow.dependencies.RecordStores;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One root's writer (P1-R §B): ONE platform thread and its queue — it blocks on JDBC, so it is never a virtual carrier of statewalk's chains.
 * Sessions are gathered into a batch (N tier records, or T after the first) and each batch is ONE transaction ({@link RecordBatch}); after
 * its commit the pings go to their own thread and every session's line in the journal of the sessions in the air is done. A batch that did
 * not commit is tried again, 1 s, 2 s, … 30 s apart.
 */
public final class RecordWriter {

    private static final Logger log = LoggerFactory.getLogger(RecordWriter.class);
    private static final long IDLE_WAIT_MS = 200;

    private final RecordStore store;
    private final RecordStores settings;
    private final PingRoad pings;
    private final SessionJournal journal;
    private final FlowCounters counters;
    private final LinkedBlockingQueue<SessionRecords> queue = new LinkedBlockingQueue<>();
    /** Sessions handed in and not yet committed and done. */
    private final AtomicInteger inHand = new AtomicInteger();
    private final Thread thread;
    private volatile boolean stopping;

    public RecordWriter(RecordStore store, RecordStores settings, PingRoad pings, SessionJournal journal, FlowCounters counters) {
        this.store = store;
        this.settings = settings;
        this.pings = pings;
        this.journal = journal;
        this.counters = counters;
        this.thread = Thread.ofPlatform().name("records-" + store.root()).daemon(true).start(this::writeWhatComes);
    }

    /** One session's records into the queue: never blocks the session's thread. */
    public void write(SessionRecords records) {
        inHand.incrementAndGet();
        queue.add(records);
    }

    /** Nothing handed in waits for its commit. */
    public boolean idle() { return inHand.get() == 0; }

    /** Stop: the batch in hand is not tried again; what was not committed stays in the journal of the sessions in the air. */
    public void stop() {
        stopping = true;
        thread.interrupt();
    }

    private void writeWhatComes() {
        while (!stopping) {
            List<SessionRecords> batch = nextBatch();
            if (!batch.isEmpty()) writeUntilItCommits(batch);
        }
    }

    /** The first session waiting, then whatever comes until the batch is full or its time is up. */
    private List<SessionRecords> nextBatch() {
        List<SessionRecords> batch = new ArrayList<>();
        try {
            SessionRecords first = queue.poll(IDLE_WAIT_MS, TimeUnit.MILLISECONDS);
            if (first == null) return batch;
            batch.add(first);
            gatherUntilFullOrDue(batch, first.size(), System.nanoTime() + settings.batchWithin().toNanos());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return batch;
    }

    private void gatherUntilFullOrDue(List<SessionRecords> batch, int records, long dueNanos) throws InterruptedException {
        while (records < settings.batchRecords()) {
            long left = dueNanos - System.nanoTime();
            if (left <= 0) return;
            SessionRecords next = queue.poll(left, TimeUnit.NANOSECONDS);
            if (next == null) return;
            batch.add(next);
            records += next.size();
        }
    }

    private void writeUntilItCommits(List<SessionRecords> batch) {
        for (int attempt = 1; !stopping; attempt++) {
            try {
                afterTheCommit(batch, RecordBatch.write(store, batch));
                return;
            } catch (SQLException | RuntimeException e) {
                sayTheFailure(batch, attempt, e);
                if (!waitBeforeTry(attempt)) return;
            }
        }
    }

    private void afterTheCommit(List<SessionRecords> batch, RecordBatch.Written written) {
        written.summedByTier().forEach(pings::offer);
        for (SessionRecords session : batch) markDone(session.sessionId());
        counters.recordBatches.incrementAndGet();
        counters.recordsWritten.addAndGet(written.writtenNow());
        counters.recordsWrittenBefore.addAndGet(written.writtenBefore());
        counters.cdrPublished.addAndGet(batch.size());
        inHand.addAndGet(-batch.size());
    }

    private void markDone(String sessionId) {
        try {
            journal.done(sessionId);
        } catch (RuntimeException e) {
            log.warn("[records {}] {} | its records are committed; its line in {} could not be marked done (a restart writes it again, once): {}",
                store.root(), sessionId, journal.where(), e.toString());
        }
    }

    private void sayTheFailure(List<SessionRecords> batch, int attempt, Exception e) {
        if (attempt == 1) log.warn("[records {}] a batch of {} session(s) did not commit, it rolled back and is tried again: {}", store.root(), batch.size(), e.toString());
    }

    /** 1 s, 2 s, 4 s, … up to the cap. False = the writer stops. */
    private boolean waitBeforeTry(int attempt) {
        long first = settings.retryFirst().toMillis(), cap = settings.retryCap().toMillis();
        long wait = Math.min(cap, first << Math.min(attempt - 1, 20));
        try {
            Thread.sleep(wait);
            return !stopping;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
