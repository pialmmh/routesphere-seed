package com.telcobright.seed.switchledger.spi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ARCH-0119 road 1 (the one-call road) · A ledger that reads, checks and writes a small batch ITSELF, under its own row locks, in one
 * call: "take 4 from this account if 4 are there, and open or grow this hold". The engine sends what to change and what must hold; the
 * ledger answers the rows as they are after. No row is read first and no lock is held across the wire, so many callers on one account
 * are served one after the other at the ledger's own speed.
 *
 * <p>What is said here is lane-neutral: fields by their names, numbers, guards. The engine asks {@link #movesInOneCall()} per verb; a
 * ledger in the process, or a root whose one-call road is off, answers false and the verb is the unit it always was.
 */
public interface PatchingLedger {

    /** Is the one-call road ON for this ledger? False = the engine never calls {@link #patch}. */
    boolean movesInOneCall();

    /** The batch: all of it or nothing. A guard not met, or a row a plain entry needs and does not find, is {@link Outcome#GUARD}. */
    Answer patch(Patch patch);

    /** A batch under its own id (a repeat under the same id is answered, never applied twice), its entries in the order they are judged. */
    record Patch(String transactionId, List<Entry> entries) {
        public Patch {
            entries = List.copyOf(entries);
        }
    }

    /** One row of the batch. */
    record Entry(String tier, String entity, Object id, Kind kind, List<Change> changes, List<Guard> guards, Object insertIfAbsent) {

        public enum Kind {
            /** The row's fields changed where it is; with {@code insertIfAbsent}, that row inserted when there is none. */
            CHANGE,
            /** The row deleted; none there = {@link Outcome#GUARD} at this entry. */
            DELETE
        }

        public Entry {
            changes = List.copyOf(changes);
            guards = List.copyOf(guards);
        }

        public static Entry change(String tier, String entity, Object id, List<Change> changes, List<Guard> guards) { return new Entry(tier, entity, id, Kind.CHANGE, changes, guards, null); }

        /** The row changed where it is, or {@code row} inserted as it is when there is none (the changes are then not applied to it). */
        public static Entry changeOrInsert(String tier, String entity, Object id, List<Change> changes, Object row) { return new Entry(tier, entity, id, Kind.CHANGE, changes, List.of(), row); }

        public static Entry delete(String tier, String entity, Object id) { return new Entry(tier, entity, id, Kind.DELETE, List.of(), List.of(), null); }
    }

    /** One field changed. */
    record Change(Op op, String field, Object value, String fromField) {

        public enum Op { ADD, SET, COPY }

        /** The number in {@code field}, plus {@code amount} (an empty field counts 0). */
        public static Change add(String field, BigDecimal amount) { return new Change(Op.ADD, field, amount, null); }

        public static Change set(String field, Object value) { return new Change(Op.SET, field, value, null); }

        /** {@code field} takes what {@code fromField} held BEFORE this entry's changes. */
        public static Change copy(String field, String fromField) { return new Change(Op.COPY, field, null, fromField); }
    }

    /** What must hold of the row AS THE ENTRY LEAVES IT, or nothing of the batch is written. */
    record Guard(Sign sign, String field, Object value) {

        public enum Sign { AT_LEAST, EQUAL }

        public static Guard atLeast(String field, BigDecimal least) { return new Guard(Sign.AT_LEAST, field, least); }

        public static Guard equal(String field, Object value) { return new Guard(Sign.EQUAL, field, value); }
    }

    enum Outcome {
        /** Written, all of it. */
        APPLIED,
        /** A guard was not met, or a row was not as an entry needs it: nothing written. A DECISION, not a fault. */
        GUARD,
        /** The rows were busy (a full queue, not free in time): nothing written, the same batch may be sent again. */
        BUSY,
        /** Refused for any other reason, in the ledger's words: nothing written. */
        OTHER
    }

    /**
     * @param failedEntry {@link Outcome#GUARD}: the entry that failed, 1 = the first
     * @param failedRow   {@link Outcome#GUARD}: that entry's row as the ledger holds it, unchanged; null = it holds none
     * @param rowsAfter   {@link Outcome#APPLIED}: per entry number, the row as the batch left it (no line for a deleted row)
     */
    record Answer(Outcome outcome, String words, int failedEntry, Object failedRow, Map<Integer, Object> rowsAfter) {

        /** The row entry {@code number} (1 = the first) left; null = none (deleted, or not applied). */
        @SuppressWarnings("unchecked")
        public <T> T rowAfter(int number) { return rowsAfter == null ? null : (T) rowsAfter.get(number); }
    }
}
