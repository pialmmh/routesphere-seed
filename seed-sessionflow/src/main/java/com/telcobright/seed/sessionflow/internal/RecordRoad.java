package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.dependencies.RecordStore;
import com.telcobright.seed.sessionflow.dependencies.RecordStores;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The records road of one kit (P1-R §B): one {@link RecordWriter} per root store — started at the root's first session — and the ping's own
 * thread. Closing drains what was handed in (bounded), then stops the writers: what did not commit stays in the journal of the sessions in
 * the air for the next start, or goes to the park sink when that journal does not hold it.
 */
public final class RecordRoad implements AutoCloseable {

    /** How long a close waits for the writers to commit what they were handed. */
    public static final long DRAIN_MS = 5_000;

    private final RecordStores stores;
    private final SessionJournal journal;
    private final FlowCounters counters;
    private final PingRoad pings;
    private final Map<String, RecordWriter> writers = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public RecordRoad(RecordStores stores, SessionJournal journal, FlowCounters counters) {
        this.stores = stores;
        this.journal = journal;
        this.counters = counters;
        this.pings = new PingRoad(stores.ping());
    }

    public RecordStores stores() { return stores; }

    /** The session's records to its root's writer. A root this road has no store for, or a closed road, is refused in words. */
    public void write(SessionRecords records) {
        if (closed) throw new IllegalStateException("the records road is closed: the records of " + records.sessionId() + " stay in the journal of the sessions in the air");
        RecordStore store = stores.of(records.root()).orElseThrow(() -> new IllegalStateException("the records road has no store for the root '"
            + records.root() + "' of " + records.sessionId()));
        writers.computeIfAbsent(store.root(), root -> new RecordWriter(store, stores, pings, journal, counters)).write(records);
    }

    /** Wait until every writer committed what it was handed. True = all committed. */
    public boolean awaitIdle(long timeoutMs) {
        long until = System.currentTimeMillis() + timeoutMs;
        while (!writers.values().stream().allMatch(RecordWriter::idle)) {
            if (System.currentTimeMillis() >= until) return false;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Commit what was handed in (at most {@link #DRAIN_MS}), then stop every writer — each says what it leaves, and where. */
    @Override
    public void close() {
        closed = true;
        awaitIdle(DRAIN_MS);
        writers.values().forEach(RecordWriter::stop);
        pings.close();
    }
}
