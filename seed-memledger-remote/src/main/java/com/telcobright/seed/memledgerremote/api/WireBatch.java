package com.telcobright.seed.memledgerremote.api;

import com.telcobright.core.wal.WALEntry;
import com.telcobright.core.wal.WALEntryBatch;
import com.telcobright.seed.memledgerremote.wire.ApplyRequest;
import com.telcobright.seed.memledgerremote.wire.Op;
import com.telcobright.seed.memledgerremote.wire.WalEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * mem-ledger's batch on the wire, both ways (design §2.1). The application's side writes the batch it would have handed to
 * {@code execCrud(WALEntryBatch)}; the server's side reads it back into the same batch — every post-image decoded into the class
 * registered under its entity's name — and hands it to mem-ledger, which checks it and refuses or applies it WHOLE. What only mem-ledger
 * can judge (an entity not registered, an operation that is not INSERT, UPDATE or DELETE, a missing id or post-image) is passed on as it
 * came, so the refusal carries mem-ledger's own words; what cannot be read at all is {@link Unreadable}, in the same words' shape.
 */
public final class WireBatch {

    /** The words mem-ledger starts every refusal of a batch with (f739824 {@code MemLedgerImpl.REFUSED}). */
    public static final String REFUSED = "Batch refused, nothing changed: ";

    private WireBatch() {}

    /** A batch that could not be read off the wire: refused before mem-ledger sees it, nothing changed. */
    public static final class Unreadable extends IllegalArgumentException {
        public Unreadable(String words) { super(REFUSED + words); }
    }

    // ── the application's side ──────────────────────────────────────────────

    /** The batch as the wire carries it; a batch with no id gets one here, so a repeat names the same batch. */
    public static ApplyRequest requestOf(WALEntryBatch batch) {
        ApplyRequest.Builder out = ApplyRequest.newBuilder().setTransactionId(idOf(batch));
        if (batch != null && batch.getEntries() != null) for (WALEntry entry : batch.getEntries()) out.addEntries(entryOf(entry));
        return out.build();
    }

    private static String idOf(WALEntryBatch batch) {
        String id = batch == null ? null : batch.getTransactionId();
        return id == null || id.isBlank() ? "TXN_" + UUID.randomUUID() : id;
    }

    private static WalEntry entryOf(WALEntry entry) {
        Op op = opOf(entry.getOperationType());
        Object image = op == Op.DELETE ? null : entry.getEntityData();
        return WalEntry.newBuilder()
            .setDbName(orEmpty(entry.getDbName()))
            .setEntityName(orEmpty(entry.getEntityType()))
            .setOp(op)
            .setEntityId(RowJson.keyText(entry.getEntityId()))
            .setEntityJson(image == null ? "" : RowJson.textOf(image))
            .build();
    }

    private static Op opOf(WALEntry.OperationType type) {
        if (type == WALEntry.OperationType.INSERT) return Op.INSERT;
        if (type == WALEntry.OperationType.UPDATE) return Op.UPDATE;
        if (type == WALEntry.OperationType.DELETE) return Op.DELETE;
        return Op.OP_UNSPECIFIED;
    }

    // ── the server's side ───────────────────────────────────────────────────

    /** The wire's batch as mem-ledger takes it; {@code registered} answers the class an entity's name is registered with (null = none). */
    public static WALEntryBatch batchOf(ApplyRequest request, Function<String, Class<?>> registered) {
        if (request.getTransactionId().isBlank()) throw new Unreadable("the batch names no transaction_id");
        List<WALEntry> entries = new ArrayList<>(request.getEntriesCount());
        for (int i = 0; i < request.getEntriesCount(); i++) {
            entries.add(entryOf(request.getEntries(i), registered, "entry " + (i + 1) + " of " + request.getEntriesCount()));
        }
        return new WALEntryBatch(request.getTransactionId(), entries);
    }

    private static WALEntry entryOf(WalEntry wire, Function<String, Class<?>> registered, String where) {
        WALEntry entry = new WALEntry();
        entry.setDbName(nullIfEmpty(wire.getDbName()));
        entry.setEntityType(nullIfEmpty(wire.getEntityName()));
        entry.setOperationType(operationOf(wire.getOp()));
        Class<?> type = registered.apply(wire.getEntityName());
        if (type == null) {                                                    // mem-ledger says "entity '…' is not registered"
            entry.setEntityId(nullIfEmpty(wire.getEntityId()));
            return entry;
        }
        try {
            entry.setEntityId(RowJson.keyOf(type, wire.getEntityId()));
            if (wire.getOp() != Op.DELETE && !wire.getEntityJson().isEmpty()) entry.setEntityData(RowJson.rowOf(type, wire.getEntityJson()));
        } catch (IllegalArgumentException e) {
            throw new Unreadable(where + " (" + wire.getDbName() + "." + wire.getEntityName() + " id=" + wire.getEntityId() + "): " + e.getMessage());
        }
        return entry;
    }

    private static WALEntry.OperationType operationOf(Op op) {
        return switch (op) {
            case INSERT -> WALEntry.OperationType.INSERT;
            case UPDATE -> WALEntry.OperationType.UPDATE;
            case DELETE -> WALEntry.OperationType.DELETE;
            default -> null;                                                   // mem-ledger says "the operation must be INSERT, UPDATE or DELETE"
        };
    }

    private static String orEmpty(String text) { return text == null ? "" : text; }

    private static String nullIfEmpty(String text) { return text == null || text.isEmpty() ? null : text; }
}
