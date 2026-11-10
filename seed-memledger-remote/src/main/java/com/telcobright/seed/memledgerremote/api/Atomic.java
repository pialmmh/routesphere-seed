package com.telcobright.seed.memledgerremote.api;

import com.telcobright.seed.memledgerremote.wire.ApplyRequest;
import com.telcobright.seed.memledgerremote.wire.Change;
import com.telcobright.seed.memledgerremote.wire.ChangeKind;
import com.telcobright.seed.memledgerremote.wire.Guard;
import com.telcobright.seed.memledgerremote.wire.GuardKind;
import com.telcobright.seed.memledgerremote.wire.Op;
import com.telcobright.seed.memledgerremote.wire.WalEntry;

import java.math.BigDecimal;
import java.util.function.Consumer;

/**
 * ARCH-0119 road 1 · ONE atomic batch, said in words: what to CHANGE in the rows the server's cache holds and what must HOLD for them
 * after the change. The server reads, changes, checks and writes it under its own row locks — one call, no lock on the wire, nothing to
 * retry. A guard that does not hold refuses the whole batch ({@link AtomicAnswer.Outcome#GUARD}): nothing is written.
 *
 * <pre>
 * Atomic.batch("call-7:reserve")
 *     .change("res_2", "PackageAccount", 11L, row -&gt; row.copy("balanceBefore", "balanceAfter").add("balanceAfter", amount.negate())
 *                                                       .set("lastAmount", amount.negate()).atLeast("balanceAfter", BigDecimal.ZERO))
 *     .change("res_2", "PackageAccountReserve", "call-7#L0", row -&gt; row.add("reserveUnit", amount).orInsert(aNewHold));
 * </pre>
 *
 * No money rule is here or on the wire: the caller names the fields and the numbers.
 */
public final class Atomic {

    private final ApplyRequest.Builder request;

    private Atomic(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) throw new IllegalArgumentException("an atomic batch needs its transaction id: a repeat under it is answered, never applied twice");
        this.request = ApplyRequest.newBuilder().setTransactionId(transactionId);
    }

    /** @param transactionId the id of this ONE batch; the same id sent again names the same batch */
    public static Atomic batch(String transactionId) { return new Atomic(transactionId); }

    /** One row changed where it is: its changes in the order said, its guards on the row after them. */
    public Atomic change(String tier, String entity, Object id, Consumer<Row> what) {
        Row row = new Row(entry(tier, entity, id).setOp(Op.PATCH));
        what.accept(row);
        request.addEntries(row.entry);
        return this;
    }

    /** The server's cache must hold NO such row (it sees what the cache holds, not what only the database holds). */
    public Atomic mustBeAbsent(String tier, String entity, Object id) {
        request.addEntries(entry(tier, entity, id).setOp(Op.PATCH).addGuards(Guard.newBuilder().setKind(GuardKind.ROW_ABSENT)));
        return this;
    }

    /** A new row, whole. */
    public Atomic insert(String tier, String entity, Object id, Object row) {
        request.addEntries(entry(tier, entity, id).setOp(Op.INSERT).setEntityJson(RowJson.textOf(row)));
        return this;
    }

    /** A row deleted. */
    public Atomic delete(String tier, String entity, Object id) {
        request.addEntries(entry(tier, entity, id).setOp(Op.DELETE));
        return this;
    }

    public String transactionId() { return request.getTransactionId(); }

    /** The batch as the wire carries it. */
    public ApplyRequest request() { return request.build(); }

    private static WalEntry.Builder entry(String tier, String entity, Object id) {
        return WalEntry.newBuilder().setDbName(tier).setEntityName(entity).setEntityId(RowJson.keyText(id));
    }

    /** One row's changes and guards. */
    public static final class Row {

        private final WalEntry.Builder entry;

        private Row(WalEntry.Builder entry) { this.entry = entry; }

        /** field = field + amount (amount may be negative). */
        public Row add(String field, BigDecimal amount) { return change(ChangeKind.ADD, field, amount.toPlainString()); }

        /** field = the smaller of field and cap. */
        public Row min(String field, BigDecimal cap) { return change(ChangeKind.MIN, field, cap.toPlainString()); }

        /** field = value: a decimal (kept as text with its scale), a whole number, a text, true/false, or null. */
        public Row set(String field, Object value) { return change(ChangeKind.SET, field, jsonOf(value)); }

        /** field = what {@code fromField} held BEFORE this row's changes. */
        public Row copy(String field, String fromField) {
            entry.addChanges(Change.newBuilder().setField(field).setKind(ChangeKind.COPY).setFromField(fromField));
            return this;
        }

        /** After the changes: field >= least. */
        public Row atLeast(String field, BigDecimal least) { return guard(GuardKind.FIELD_GE, field, least.toPlainString()); }

        /** After the changes: field <= most. */
        public Row atMost(String field, BigDecimal most) { return guard(GuardKind.FIELD_LE, field, most.toPlainString()); }

        /** After the changes: field = value (two numbers by their value; a word or a date as text). */
        public Row equalTo(String field, Object value) { return guard(GuardKind.FIELD_EQ, field, value instanceof BigDecimal d ? d.toPlainString() : String.valueOf(value)); }

        /** After the changes: field >= text, as text (an ISO date or date-time of one shape orders as its text does). */
        public Row notBefore(String field, String text) { return guard(GuardKind.FIELD_GE, field, text); }

        /** The cache holds no such row: insert this one instead (the changes are not applied to it; the guards are). */
        public Row orInsert(Object row) {
            entry.setInsertIfAbsent(true).setEntityJson(RowJson.textOf(row));
            return this;
        }

        private Row change(ChangeKind kind, String field, String value) {
            entry.addChanges(Change.newBuilder().setField(field).setKind(kind).setValue(value));
            return this;
        }

        private Row guard(GuardKind kind, String field, String value) {
            entry.addGuards(Guard.newBuilder().setKind(kind).setField(field).setValue(value));
            return this;
        }

        private static String jsonOf(Object value) {
            if (value == null) return "null";
            if (value instanceof BigDecimal d) return quoted(d.toPlainString());
            if (value instanceof Long || value instanceof Integer || value instanceof Boolean) return String.valueOf(value);
            return quoted(String.valueOf(value));
        }

        private static String quoted(String text) { return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"'; }
    }
}
