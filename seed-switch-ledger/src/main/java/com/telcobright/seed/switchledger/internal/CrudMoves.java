package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.sessionflow.api.CachedLedger.Check;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * C1 · The moves of a cached-ledger unit, each staged on the unit of work at the commit, in the order named, on the rows as the moves
 * before it left them ({@link UnitOfWork#read}). A move answers null when it is staged, or its refusal in words — the commit then writes
 * nothing. An account's {@code balanceAfter} moves as {@link Books#moveBalance} moves it: before = old, after = old + delta, last = delta.
 */
final class CrudMoves {

    static final String BALANCE = "balanceAfter";

    private CrudMoves() {}

    /** One move: its row (or rows), the lock it takes, its staging. */
    sealed interface Move permits Insert, Update, Add, Delete, Require, RequireNone {
        String tier();

        String entity();

        AccountLocks.Key lockKey();

        /** Stage the move; null = staged, else the refusal in words. */
        String stageOn(UnitOfWork work);
    }

    /** Stage every move in order; the first refusal stops it. */
    static String stageAll(List<Move> moves, UnitOfWork work) {
        for (Move move : moves) {
            String refusal = move.stageOn(work);
            if (refusal != null) return refusal;
        }
        return null;
    }

    record Insert(String tier, String entity, Object id, Object row) implements Move {
        public AccountLocks.Key lockKey() { return AccountLocks.Key.row(tier, entity, id); }

        public String stageOn(UnitOfWork work) {
            if (work.read(tier, entity, id) != null) return tier + " already holds " + entity + " " + id;
            work.insert(tier, entity, id, row);
            return null;
        }
    }

    record Update(String tier, String entity, Object id, Map<String, Object> set) implements Move {
        public AccountLocks.Key lockKey() { return AccountLocks.Key.row(tier, entity, id); }

        public String stageOn(UnitOfWork work) {
            Object live = work.read(tier, entity, id);
            if (live == null) return notHeld(tier, entity, id);
            Object next = RowFields.copyOf(live);
            set.forEach((field, value) -> RowFields.set(next, field, value));
            work.update(tier, entity, id, next);
            return null;
        }
    }

    record Add(String tier, String entity, Object id, String field, BigDecimal delta) implements Move {
        public AccountLocks.Key lockKey() { return AccountLocks.Key.row(tier, entity, id); }

        public String stageOn(UnitOfWork work) {
            Object live = work.read(tier, entity, id);
            if (live == null) return notHeld(tier, entity, id);
            BigDecimal old = decimalOf(RowFields.get(live, field));
            Object next = RowFields.copyOf(live);
            if (Books.ACCOUNT.equals(entity) && BALANCE.equals(field)) {
                RowFields.set(next, "balanceBefore", old);
                RowFields.set(next, "lastAmount", delta);
            }
            RowFields.set(next, field, old.add(delta));
            work.update(tier, entity, id, next);
            return null;
        }
    }

    record Delete(String tier, String entity, Object id) implements Move {
        public AccountLocks.Key lockKey() { return AccountLocks.Key.row(tier, entity, id); }

        public String stageOn(UnitOfWork work) {
            if (work.read(tier, entity, id) == null) return notHeld(tier, entity, id);
            work.delete(tier, entity, id);
            return null;
        }
    }

    record Require(String tier, String entity, Object id, Check check) implements Move {
        public AccountLocks.Key lockKey() { return AccountLocks.Key.row(tier, entity, id); }

        public String stageOn(UnitOfWork work) {
            Object live = work.read(tier, entity, id);
            String row = entity + " " + id + " of " + tier;
            if (check.kind() == Check.Kind.ABSENT) return live == null ? null : row + " is there (require absent)";
            if (live == null) return row + " is not there (require " + check.kind().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")";
            Object value = check.field() == null ? null : RowFields.get(live, check.field());
            return switch (check.kind()) {
                case AT_LEAST -> decimalOf(value).compareTo((BigDecimal) check.value()) >= 0 ? null
                    : row + ": " + check.field() + " is " + said(value) + ", below " + said(check.value()) + " (require at least)";
                case EQUAL_TO -> RowFields.holds(live, check.field(), check.value()) ? null
                    : row + ": " + check.field() + " is " + said(value) + ", not " + said(check.value()) + " (require equal to)";
                default -> null;
            };
        }
    }

    /** No row may match; the lock is the predicate's own, so units asking the same {@code requireNone} run one after the other. */
    record RequireNone(String tier, String entity, Map<String, Object> equals) implements Move {
        public AccountLocks.Key lockKey() { return new AccountLocks.Key(tier, entity, "?" + canonical(equals)); }

        public String stageOn(UnitOfWork work) {
            for (Object row : work.rowsAsLeft(tier, entity)) {
                if (RowFields.matches(row, equals)) return entity + " " + RowFields.keyOf(row) + " of " + tier + " matches " + equals + " (require none)";
            }
            return null;
        }
    }

    private static String notHeld(String tier, String entity, Object id) { return entity + " " + id + " is not held in " + tier; }

    /** A value in words: a number plain (50.000000, never 5E+1). */
    static String said(Object value) { return value instanceof BigDecimal d ? d.toPlainString() : String.valueOf(value); }

    static BigDecimal decimalOf(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }

    /** The same fields and values in one spelling, whatever the map's order (numbers by value). */
    static String canonical(Map<String, Object> equals) {
        StringBuilder text = new StringBuilder();
        new TreeMap<>(equals).forEach((field, value) -> text.append(field).append('=').append(spelled(value)).append('&'));
        return text.toString();
    }

    private static String spelled(Object value) {
        return value instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : String.valueOf(value);
    }
}
