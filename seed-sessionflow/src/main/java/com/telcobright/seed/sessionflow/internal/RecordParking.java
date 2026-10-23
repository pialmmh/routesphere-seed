package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The failure road of one root's writer (P1-R §B). A batch that did not commit is tried again; when its tries reach the budget the writer is
 * PARKED: said ONCE (ERROR, with the root and the cause), its records counted ({@code recordsParked}), every session left in the journal of
 * the sessions in the air — nothing is lost, the next start writes it — and a session that journal does not hold written to the park sink
 * (the file CDR journal, as today). While parked, a session handed in goes the same way. The next commit UN-PARKS it, said once.
 */
final class RecordParking {

    private static final Logger log = LoggerFactory.getLogger(RecordParking.class);

    private final String root;
    private final int budget;
    private final SessionJournal journal;
    private final CdrSink parkInto;
    private final FlowCounters counters;
    private final Set<String> inThePark = ConcurrentHashMap.newKeySet();
    private volatile boolean parked;
    private volatile long parkedAtMs;

    RecordParking(String root, int budget, SessionJournal journal, CdrSink parkInto, FlowCounters counters) {
        this.root = root;
        this.budget = budget;
        this.journal = journal;
        this.parkInto = parkInto;
        this.counters = counters;
    }

    boolean parked() { return parked; }

    /** A try of a batch failed: the first is a WARN; the budget's last parks the writer, with what waits behind it. */
    void failed(List<SessionRecords> batch, int attempt, Exception cause, Collection<SessionRecords> waiting) {
        if (parked) return;
        if (attempt == 1) log.warn("[records {}] a batch of {} session(s) did not commit; it rolled back and is tried again: {}", root, batch.size(), cause.toString());
        if (attempt >= budget) park(batch, cause, waiting);
    }

    /** A session handed in while the writer is parked is kept off this process alone at once. */
    void handedIn(SessionRecords session) {
        if (parked) keepWhatTheJournalDoesNotHold(List.of(session));
    }

    /** A batch committed: a parked writer is un-parked, said once. */
    void committed(int sessions) {
        if (!parked) return;
        parked = false;
        counters.recordUnparks.incrementAndGet();
        log.warn("[records {}] UNPARKED: the store took the batch again after {} s; the writer goes on ({} session(s) in this batch)", root,
            (System.currentTimeMillis() - parkedAtMs) / 1000, sessions);
    }

    /** The close left these not committed: in the journal they stay; the rest to the park sink. Said once. */
    void leftAtTheClose(Collection<SessionRecords> left) {
        if (left.isEmpty()) return;
        int kept = keepWhatTheJournalDoesNotHold(left);
        log.warn("[records {}] closed with {} session(s) not committed: they stay in {} for the next start; {} the journal does not hold went to {}", root,
            left.size(), journal.where(), kept, whereTheParkIs());
    }

    private void park(List<SessionRecords> batch, Exception cause, Collection<SessionRecords> waiting) {
        parked = true;
        parkedAtMs = System.currentTimeMillis();
        int records = batch.stream().mapToInt(SessionRecords::size).sum();
        counters.recordParks.incrementAndGet();
        counters.recordsParked.addAndGet(records);
        int kept = keepWhatTheJournalDoesNotHold(batch) + keepWhatTheJournalDoesNotHold(waiting);
        log.error("[records {}] PARKED: a batch of {} session(s), {} record(s), did not commit in {} tries: {} — nothing is lost: each session stays in {}"
            + " for the next start, and {} the journal does not hold went to {}; the writer tries again at its longest wait", root, batch.size(), records,
            budget, cause.toString(), journal.where(), kept, whereTheParkIs());
    }

    /** The sessions the journal of the sessions in the air does not hold, written to the park sink once each; how many. */
    private int keepWhatTheJournalDoesNotHold(Collection<SessionRecords> sessions) {
        int kept = 0;
        for (SessionRecords session : sessions) {
            if (journal.holds(session.sessionId()) || !inThePark.add(session.sessionId())) continue;
            if (parkInto != null) parkOne(session);
            kept++;
        }
        return kept;
    }

    private void parkOne(SessionRecords session) {
        List<CdrEvent> records = session.tiers().stream().map(SessionRecords.TierRecord::record).toList();
        try {
            parkInto.publish(root, session.sessionId(), records);
        } catch (RuntimeException e) {
            log.error("[records {}] {} | its records could not be kept in {}: {} — they live in this process only", root, session.sessionId(), whereTheParkIs(), e.toString());
        }
    }

    private String whereTheParkIs() { return parkInto == null ? "nowhere but this process (no park sink)" : "the park sink " + parkInto.getClass().getSimpleName(); }
}
