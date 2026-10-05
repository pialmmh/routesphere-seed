package com.telcobright.seed.callflow.spi;

import java.util.List;

/**
 * The calls in the air, on disk (ARCH-0049 R1-6): every taka the switch reserved ends in a record or goes back — a process death
 * included. A reserve lives in the process's memory until the call's end publishes its record; a process that dies in between
 * leaves the money taken and no record of it. This journal closes that gap for every call that was HANDED OVER (the instant the
 * application gives the call to its user: an ad's start road hands the session to the phone):
 *
 * <pre>
 *   the hand-over   ONE line, written on the call's own thread before the call is handed over, with its records already made as
 *                   the start would publish them: ended LOST_AT_RESTART, every tier charged what it reserved
 *   on the way      small lines with what the switch learns later (the answer, the billed seconds)
 *   the end         the record the call's end published marks its line done
 *   the next start  every line not done is published, before the first call: those calls WERE handed over
 * </pre>
 *
 * A call whose line cannot be written is not handed over: the caller refuses it and every reserve goes back. A reserve of a call that
 * was never handed over has no line here: if the process dies between the reserve and the hand-over, only the ledger knows it.
 */
public interface CallJournal {

    /** The call is handed over: {@code records} = its records as the next start would publish them (the CDR message, a JSON array). Throws when the line is not written. */
    void handedOver(String callId, long atMs, String records);

    /** What the switch learned of a call in the air since its hand-over. A call with no line here is not noted. */
    void noted(String callId, long atMs, long answeredAtMs, double billedSec);

    /** The call's own end published its record: its line is done. A call with no line here is nothing to do. */
    void done(String callId);

    /** The calls a stopped process left in the air: read once, when the journal is opened. */
    List<Leftover> leftovers();

    /** Where the journal lies, for a log line. */
    String where();

    /** False = the deployment keeps no journal: a call is handed over with no line, and a process death loses its record. */
    default boolean keeps() { return true; }

    default void close() { }

    /**
     * One call a stopped process left in the air.
     *
     * @param records      its records as made at the hand-over (the CDR message, a JSON array)
     * @param answeredAtMs the answer the switch had learned, 0 = none
     * @param billedSec    the seconds the switch had learned, 0 = none
     * @param lastAtMs     the last moment the switch knew of the call: the record's end
     */
    record Leftover(String callId, long handedOverAtMs, String records, long answeredAtMs, double billedSec, long lastAtMs) {}

    /** No journal. */
    CallJournal NONE = new CallJournal() {
        @Override public void handedOver(String callId, long atMs, String records) { }
        @Override public void noted(String callId, long atMs, long answeredAtMs, double billedSec) { }
        @Override public void done(String callId) { }
        @Override public List<Leftover> leftovers() { return List.of(); }
        @Override public String where() { return "no journal"; }
        @Override public boolean keeps() { return false; }
    };
}
