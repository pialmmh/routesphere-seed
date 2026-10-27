package com.telcobright.seed.sessionflow.api;

import com.telcobright.seed.sessionflow.spi.LedgerPort;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * C1 · The cached ledger — the CRUD of the tables a root's ledger caches (design §14: the owner, 2026-10-28: "just offer the cached ledger
 * service through it … the crud api"). The application that owns the cache (the one that runs the sessions, D1) gets it from its kit
 * ({@code kit.ledger().cachedLedgerOf(root)}) and may expose it on its own roads (C2); the seed has no server (R0). Every business rule of a
 * purchase stays with the caller (TelcoREST's in TelcoREST, the officer's in the application).
 *
 * <p>Rows are the ledger's entity classes ({@code PackageAccount}, {@code LedgerPurchase} …); a field is named by its Java field name. A
 * read answers COPIES — never the cached object. A {@link Unit} is ONE write: every operation it names, over every tier, lands as ONE WAL
 * record, or nothing does.
 *
 * <ul>
 *   <li><b>A balance moves by {@link Unit#add}</b> — a delta, as the live recharge does: the sessions' reserves on the same account are never
 *       undone. A {@link Unit#update} that sets a balance must first {@link Unit#require} the value it replaces ({@link Check#equalTo}),
 *       or it is refused in words.</li>
 *   <li><b>The sessions' rows are read-only here</b>: a reserve row or a charge's reference row is read, never written.</li>
 *   <li><b>Refusals:</b> a caller's mistake (an unknown field, a type the ledger does not cache, a session's row, an unchecked balance, a
 *       row with no key and no id source) is an {@link IllegalArgumentException} at once; what the rows say at the commit (a failed check, a
 *       row missing or already there) is {@link Refused}, nothing written; a ledger that does not answer, or a lock not free in time, is a
 *       {@link LedgerPort.LedgerFault}.</li>
 * </ul>
 */
public interface CachedLedger {

    /** A copy of the row {@code tier} holds under {@code id}; empty = none. */
    <T> Optional<T> get(String tier, Class<T> type, Object id);

    /** Copies of every row of {@code type} in {@code tier} whose named fields equal the values given (none named = every row), by key. */
    <T> List<T> find(String tier, Class<T> type, Map<String, ?> equals);

    /** The same ledger; a row inserted with no key gets one from {@code ids} (the application's source: bucket-next), above the highest held. */
    CachedLedger withIds(LedgerPort.Ids ids);

    /** A unit with no reference: every commit writes. */
    Unit unit();

    /** A unit known by {@code reference}: the first commit writes; the same reference again answers the first, nothing written. */
    Unit unit(String reference);

    /** ONE write over every tier it names. Operations run at the commit, in the order named, each on the rows as the ones before left them. */
    interface Unit {

        /** Insert {@code row} (a new object of a cached type); a null key is minted now. @return the row's key */
        <K> K insert(String tier, Object row);

        /** Set the named fields of the row {@code id}; the rest of the row stays. */
        Unit update(String tier, Class<?> type, Object id, Map<String, ?> set);

        /** Add {@code delta} (either sign) to a number field. An account's {@code balanceAfter}: before = old, after = old + delta, last = delta. */
        Unit add(String tier, Class<?> type, Object id, String field, BigDecimal delta);

        Unit delete(String tier, Class<?> type, Object id);

        /** The row {@code id} must pass {@code check} here, or the unit is refused. */
        Unit require(String tier, Class<?> type, Object id, Check check);

        /** No row of {@code type} may match {@code equals} here. Units that ask the same {@code requireNone} run one after the other. */
        Unit requireNone(String tier, Class<?> type, Map<String, ?> equals);

        /** Write it all as ONE record: {@link Committed} or {@link Refused}. A unit commits once. */
        Outcome commit();
    }

    /** A check of one row. */
    record Check(Kind kind, String field, Object value) {

        public enum Kind { EXISTS, ABSENT, AT_LEAST, EQUAL_TO }

        public static Check exists() { return new Check(Kind.EXISTS, null, null); }

        public static Check absent() { return new Check(Kind.ABSENT, null, null); }

        /** The row exists and its number field is at least {@code value}. */
        public static Check atLeast(String field, BigDecimal value) { return new Check(Kind.AT_LEAST, field, value); }

        /** The row exists and its field equals {@code value} (numbers by value). */
        public static Check equalTo(String field, Object value) { return new Check(Kind.EQUAL_TO, field, value); }
    }

    /** What a commit came to. */
    sealed interface Outcome permits Committed, Refused {}

    /**
     * Written as ONE record.
     *
     * @param keys     the keys of the rows inserted, in order; for a repeat, the FIRST commit's first key (none when it inserted nothing)
     * @param rows     copies of every row inserted or changed, as written, in order; none for a repeat
     * @param repeated the reference was committed before: nothing was written now
     */
    record Committed(List<Object> keys, List<Object> rows, boolean repeated) implements Outcome {
        public Committed {
            keys = List.copyOf(keys);
            rows = List.copyOf(rows);
        }
    }

    /** Nothing written: {@code reason} says which row and which check. */
    record Refused(String reason) implements Outcome {}
}
