package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.spi.SessionJournal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sessions in the air of a kit made from the bootstrap (R1-6): one journal per served root, in the root's own directory. A session's
 * lines go to its root's journal — named by its first reserve, else by its records' root at the hand-over; a later line follows the first.
 * The next start reads every root's leftovers.
 */
public final class PerRootJournal implements SessionJournal {

    private final Map<String, SessionJournal> byRoot = new ConcurrentHashMap<>();
    private final Map<String, String> rootOfSession = new ConcurrentHashMap<>();

    public void add(String root, SessionJournal journal) { byRoot.put(root, journal); }

    /** P1-H · A root's journal taken out again (its roads could not all be made); the caller closes it. */
    public SessionJournal remove(String root) { return byRoot.remove(root); }

    @Override
    public void reserved(String callId, long atMs, String records, Held held) {
        String root = held.root() != null ? held.root() : rootOfRecords(records);
        journalOf(callId, root).reserved(callId, atMs, records, held);
    }

    @Override public void released(String callId, String reference) { eachJournalOf(callId).forEach(j -> j.released(callId, reference)); }

    @Override public void handedOver(String callId, long atMs, String records) { journalOf(callId, rootOfRecords(records)).handedOver(callId, atMs, records); }

    @Override public void noted(String callId, long atMs, long answeredAtMs, double billedSec) { eachJournalOf(callId).forEach(j -> j.noted(callId, atMs, answeredAtMs, billedSec)); }

    @Override
    public void done(String callId) {
        eachJournalOf(callId).forEach(j -> j.done(callId));
        rootOfSession.remove(callId);
    }

    @Override
    public List<Leftover> leftovers() {
        List<Leftover> all = new ArrayList<>();
        byRoot.values().forEach(j -> all.addAll(j.leftovers()));
        return all;
    }

    @Override public String where() { return "one journal per root " + byRoot.keySet(); }

    @Override public boolean keeps() { return byRoot.values().stream().anyMatch(SessionJournal::keeps); }

    @Override public void close() { byRoot.values().forEach(SessionJournal::close); }

    /** The session's root journal: the one it was first written to, else its root's; a session of no served root is said in words. */
    private SessionJournal journalOf(String callId, String root) {
        String known = rootOfSession.computeIfAbsent(callId, id -> root);
        SessionJournal journal = known == null ? null : byRoot.get(known);
        if (journal == null) throw new IllegalStateException("the session " + callId + " names no served root's journal (root '" + known + "', roots " + byRoot.keySet() + ")");
        return journal;
    }

    /** The journal a session was written to; a session this process never wrote (a leftover of a stopped one) — every root's, each ignores a call it has no line for. */
    private List<SessionJournal> eachJournalOf(String callId) {
        String known = rootOfSession.get(callId);
        SessionJournal journal = known == null ? null : byRoot.get(known);
        return journal != null ? List.of(journal) : List.copyOf(byRoot.values());
    }

    private static String rootOfRecords(String records) {
        List<CdrEvent> tiers = records == null ? List.of() : CdrJson.toCall(records);
        return tiers.isEmpty() ? null : CdrAssembler.rootOf(tiers.get(0));
    }
}
