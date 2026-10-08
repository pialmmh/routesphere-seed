package com.telcobright.seed.callflow.spi;

import java.math.BigDecimal;
import java.util.List;

/**
 * The calls in the air, on disk (ARCH-0049 R1-6; ARCH-0065 F9): every taka the switch reserved ends in a record or goes back — a
 * process death included. A reserve lives in the process's memory until the call's end publishes its record; a process that dies in
 * between leaves the money taken and no record of it. This journal closes that gap for every call from its FIRST RESERVE on:
 *
 * <pre>
 *   the first reserve   ONE line, written on the call's own thread the moment the first tier's money is held — before the next tier's
 *                       is asked — with the record the next start publishes if the call is never handed over: the entry tier at 0.00,
 *                       ended LOST_AT_RESTART; then one small line per reserve as it is held (the tier, the reference, the amount)
 *   a refused candidate the reserves the switch gave back itself: one small line per reference (the next start leaves them)
 *   the hand-over       ONE line, written before the call is handed over, with its records already made as the start would publish
 *                       them: ended LOST_AT_RESTART, every tier charged what it reserved
 *   on the way          small lines with what the switch learns later (the answer, the billed seconds)
 *   the end             the record the call's end published marks its line done
 *   the next start      every line not done is read, before the first call: a call HANDED OVER is published as its hand-over line
 *                       says; a call with reserves and NO hand-over gets every reserve given back (the ledger's release by reference —
 *                       the return road, else its owed journal) and its entry-tier record at 0.00
 * </pre>
 *
 * A call whose hand-over line cannot be written is not handed over: the caller refuses it and every reserve goes back. A reserve line
 * that cannot be written is said once and the call goes on: the hand-over's line is the belt it was before F9.
 */
public interface CallJournal {

    /**
     * One reserve of a call, as held: enough for the next start to give it back by its reference.
     *
     * @param root   the call's own tenant (the root the call named), null when it named none
     * @param tenant the tier's database name
     */
    record Held(int tier, String root, String tenant, int partnerId, Long account, String uom, BigDecimal amount, String reference) {}

    /**
     * A tier's money is held (F9). The FIRST call for a call id opens its line with {@code records} — the record the next start publishes
     * if the call is never handed over (the entry tier at 0.00, ended LOST_AT_RESTART); every call adds the reserve. Throws when the line
     * is not written. A journal that does not keep the reserves (an older one) ignores it: the hand-over line is its belt.
     */
    default void reserved(String callId, long atMs, String records, Held held) { }

    /** The switch itself gave a reserve back (a refused candidate): the next start leaves it. */
    default void released(String callId, String reference) { }

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
     * @param handedOver   true = the hand-over line was written: the records are the hand-over's, every tier charged what it reserved;
     *                     false = the call had reserves and no hand-over (F9): the records are the entry tier at 0.00, and
     *                     {@code reserves} are to be given back
     * @param reserves     the reserves still held when the process died (the ones the switch gave back itself are not here)
     * @param records      its records as made at the hand-over, or at the first reserve (the CDR message, a JSON array)
     * @param answeredAtMs the answer the switch had learned, 0 = none
     * @param billedSec    the seconds the switch had learned, 0 = none
     * @param lastAtMs     the last moment the switch knew of the call: the record's end
     */
    record Leftover(String callId, long handedOverAtMs, String records, long answeredAtMs, double billedSec, long lastAtMs,
                    boolean handedOver, List<Held> reserves) {
        public Leftover {
            reserves = reserves == null ? List.of() : List.copyOf(reserves);
        }

        /** A call that was handed over (the shape before F9). */
        public Leftover(String callId, long handedOverAtMs, String records, long answeredAtMs, double billedSec, long lastAtMs) {
            this(callId, handedOverAtMs, records, answeredAtMs, billedSec, lastAtMs, true, List.of());
        }
    }

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
