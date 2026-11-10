package com.telcobright.seed.switchledger.internal;

import com.telcobright.core.cache.CacheableEntity;
import com.telcobright.core.wal.WALEntry;
import com.telcobright.core.wal.WALEntryBatch;
import com.telcobright.memledger.api.MemLedger;
import com.telcobright.memledger.api.response.EntityResponse;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.switchledger.spi.ExpectingLedger;
import com.telcobright.seed.switchledger.spi.ExpectingLedger.AsRead;
import com.telcobright.seed.switchledger.spi.LeasingLedger;
import com.telcobright.seed.switchledger.spi.LeasingLedger.RowsBusy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * L2b item 1 · ONE write path of the switch ledger: the post-images {@link Books} builds — an account's balance moved, debited or
 * reparented, a reserve row opened, grown or deleted — are COLLECTED here as entries {@code (tier, entity, op, id, post-image)}, and
 * {@link #commit} sends them as ONE {@code MemLedger.execCrud(WALEntryBatch)}: ONE WAL record over every tier they name (mem-ledger
 * 6523614, L1: lock every key → check every entry → append one record → publish the post-images). A refusal of the ledger is a
 * {@link LedgerFault} in its words, and nothing changed in any tier.
 *
 * <p>Inside a unit a row staged earlier is read FROM THE UNIT ({@link #read}), never from the cache — the cache still holds the old row
 * until the commit. Two moves of the same row are MERGED into one entry (mem-ledger refuses the same row twice in one batch):
 * INSERT+UPDATE → INSERT of the last image · UPDATE+UPDATE → UPDATE of the last image · INSERT+DELETE → nothing · UPDATE+DELETE → DELETE ·
 * DELETE+INSERT → UPDATE of the new image; anything else (a row deleted, then moved; a row inserted twice) is refused in words.
 *
 * <p>The images staged are NEW objects (a copy of the cached row with its change): the cached row is never changed in place before the
 * WAL has the record (design §3, A3). Not thread-safe: one unit belongs to one money operation, under the seed's account locks.
 *
 * <p>ARCH-0118 S2 · The unit remembers, per row, the FIRST row it read FROM THE LEDGER ({@link #read}; never one read back from its own
 * staged rows) — or that there was none. Over a ledger that checks them ({@link ExpectingLedger}: a recall-db two applications write
 * through) the commit sends them with the batch: one that is not what the unit read refuses the whole batch STALE, nothing written —
 * today's {@link LedgerFault} in the ledger's words, no retry here. Over any other ledger the commit is exactly the one
 * {@code execCrud(batch)} it always was. Whole-table reads ({@link #rowsAsLeft}) are NOT remembered: what a rule checked over a whole
 * table (a row that must not exist, a sum) is not guarded against the other writer.
 *
 * <p>ARCH-0119 road 2 (the lock road) · Over a ledger that HOLDS rows ({@link LeasingLedger#holdsRows()}) a unit that named the rows
 * it will read ({@link #willRead}) holds them — all or none, ONE call — at its first read, reads them FROM THAT HOLD, and writes its one
 * batch under it: no other writer, of this process or another application, changes a held row in between, so two units on one account
 * take turns. A row named after the hold was taken, or not named at all, is read and remembered as read exactly as above. Busy rows (not
 * free in time, a full queue) and a hold lost before the write are a {@link LedgerFault#busy() busy} fault: nothing written, the same call
 * may be made again. A unit that will not commit gives its rows back with {@link #release}. Over any other ledger — and for a unit that
 * named no row — nothing here differs from the unit it was.
 */
public final class UnitOfWork {

    /** What a staged row becomes at the commit. */
    public enum Op { INSERT, UPDATE, DELETE }

    private record RowKey(String tier, String entity, String id) {}

    private static final class Staged {
        final String tier;
        final String entity;
        final Object id;
        Op op;
        Object image;

        Staged(String tier, String entity, Object id, Op op, Object image) {
            this.tier = tier;
            this.entity = entity;
            this.id = id;
            this.op = op;
            this.image = image;
        }
    }

    private final MemLedger ledger;
    private final Map<RowKey, Staged> staged = new LinkedHashMap<>();
    /** ARCH-0118 S2 · The first row read from the ledger per key (row null = none there), in the order first read. */
    private final Map<RowKey, AsRead> firstRead = new LinkedHashMap<>();
    /** ARCH-0119 · The ledger's lock road, or null: this ledger holds no rows (in the process, or the road is off). */
    private final LeasingLedger leasing;
    /** ARCH-0119 · The rows this unit named before its first read, in the order named, each once. */
    private final Map<RowKey, LeasingLedger.Row> named = new LinkedHashMap<>();
    /** ARCH-0119 · The hold over the named rows — taken at the first read, gone with the write or the release. */
    private LeasingLedger.Held hold;
    private boolean holdTaken;
    private boolean committed;

    public UnitOfWork(MemLedger ledger) {
        this.ledger = ledger;
        this.leasing = ledger instanceof LeasingLedger road && road.holdsRows() ? road : null;
    }

    // ── ARCH-0119 road 2: the rows this unit holds ──────────────────────────

    /**
     * A row this unit will read and may change: over a ledger that holds rows it is held with the unit's other named rows at the unit's
     * first read. Named after that read it is read as any row (and travels as read). Over any other ledger: nothing.
     */
    public UnitOfWork willRead(String tier, String entity, Object id) {
        if (leasing != null && !holdTaken) named.putIfAbsent(keyOf(tier, entity, id), new LeasingLedger.Row(tier, entity, id));
        return this;
    }

    /** Does this unit hold the row on the ledger's lock road? */
    public boolean holds(String tier, String entity, Object id) { return hold != null && hold.holds(tier, entity, id); }

    /** This unit will not write: its held rows go back now. Safe to call twice, and after a commit. */
    public void release() {
        LeasingLedger.Held mine = hold;
        hold = null;
        if (mine != null) leasing.release(mine);
    }

    /** The hold, taken ONCE — at the first read after a row was named: all the named rows or none (the first ones when more are named than one hold may name). */
    private void holdTheNamedRowsOnce() {
        if (leasing == null || holdTaken || named.isEmpty()) return;
        holdTaken = true;
        List<LeasingLedger.Row> rows = named.values().stream().limit(Math.max(1, leasing.rowsOneHoldMayName())).toList();
        hold = busyIsAFaultOfItsOwn("hold " + rows.size() + " row(s) for a unit (" + whatOf(rows.get(0)) + (rows.size() > 1 ? ", …" : "") + ")", () -> leasing.hold(rows));
    }

    private static String whatOf(LeasingLedger.Row row) { return row.entity() + " " + row.id() + " of " + row.tier(); }

    /** {@link Books#guarded}, with the ledger's "busy" kept apart: a {@link LedgerFault#busy() busy} fault, never the broken-ledger one. */
    private static <T> T busyIsAFaultOfItsOwn(String what, java.util.function.Supplier<T> call) {
        try {
            return Books.guarded(what, call);
        } catch (LedgerFault fault) {
            if (fault.getCause() instanceof RowsBusy busy) {
                throw LedgerFault.ofBusyRows("the ledger's rows were busy, nothing was written (" + busy.code() + "): could not " + what + " — " + busy.getMessage(), busy);
            }
            throw fault;
        }
    }

    // ── reads: the unit first ───────────────────────────────────────────────

    /** The row as this unit will leave it: a row staged here (null when the unit deletes it), else the ledger's cached row — remembered as read. */
    @SuppressWarnings("unchecked")
    public <T> T read(String tier, String entity, Object id) {
        RowKey key = keyOf(tier, entity, id);
        Staged row = staged.get(key);
        if (row != null) return row.op == Op.DELETE ? null : (T) row.image;
        holdTheNamedRowsOnce();
        if (hold != null && hold.holds(tier, entity, id)) return hold.row(tier, entity, id);      // held: no other writer changes it before this unit's write
        T cached = Books.guarded("read " + entity + " " + id + " of " + tier, () -> ledger.getEntity(tier, entity, id));
        firstRead.putIfAbsent(key, new AsRead(tier, entity, id, cached));
        return cached;
    }

    /**
     * C1 · Every row of {@code entity} in {@code tier} as this unit will leave it: the cached rows, the unit's own moves over them. A
     * whole-table read: its rows are NOT remembered as read (ARCH-0118 — only a row read by its key travels with the batch).
     */
    public List<Object> rowsAsLeft(String tier, String entity) {
        Map<String, Object> rows = new LinkedHashMap<>();
        Map<?, ?> cached = Books.guarded("list " + entity + " of " + tier, () -> ledger.<Object, CacheableEntity<Object, ?>>getAllEntities(tier, entity));
        cached.forEach((key, row) -> rows.put(String.valueOf(key), row));
        for (Staged row : staged.values()) {
            if (!row.tier.equals(tier) || !row.entity.equals(entity)) continue;
            if (row.op == Op.DELETE) rows.remove(String.valueOf(row.id)); else rows.put(String.valueOf(row.id), row.image);
        }
        return new ArrayList<>(rows.values());
    }

    /** C2 · One row this unit writes: its tier, its entity, the op, the image (null for a delete), its key. */
    public record Row(String tier, String entity, Op op, Object id, Object image) {}

    /** C2 · Every row this unit writes, in the order first staged. */
    public List<Row> rows() { return staged.values().stream().map(row -> new Row(row.tier, row.entity, row.op, row.id, row.image)).toList(); }

    /** C1 · The rows this unit writes (its inserts and updates, not its deletes), as it writes them, in the order first staged. */
    public List<Object> images() { return staged.values().stream().filter(row -> row.op != Op.DELETE).map(row -> row.image).toList(); }

    // ── staging ─────────────────────────────────────────────────────────────

    public UnitOfWork insert(String tier, String entity, Object id, Object image) { return stage(tier, entity, id, Op.INSERT, image); }

    public UnitOfWork update(String tier, String entity, Object id, Object image) { return stage(tier, entity, id, Op.UPDATE, image); }

    public UnitOfWork delete(String tier, String entity, Object id) { return stage(tier, entity, id, Op.DELETE, null); }

    private UnitOfWork stage(String tier, String entity, Object id, Op op, Object image) {
        if (committed) throw new IllegalStateException("this unit was committed: a new money operation opens a new unit");
        RowKey key = keyOf(tier, entity, id);
        Staged before = staged.get(key);
        if (before == null) staged.put(key, new Staged(tier, entity, id, op, image));
        else merge(key, before, op, image);
        return this;
    }

    /** The same row moved twice in one unit: ONE entry, the last image (the rules in the class's words). */
    private void merge(RowKey key, Staged before, Op op, Object image) {
        switch (before.op) {
            case INSERT -> {
                if (op == Op.UPDATE) before.image = image;
                else if (op == Op.DELETE) staged.remove(key);
                else throw refusedMerge(key, before.op, op);
            }
            case UPDATE -> {
                if (op == Op.UPDATE) before.image = image;
                else if (op == Op.DELETE) { before.op = Op.DELETE; before.image = null; }
                else throw refusedMerge(key, before.op, op);
            }
            case DELETE -> {
                if (op != Op.INSERT) throw refusedMerge(key, before.op, op);
                before.op = Op.UPDATE;
                before.image = image;
            }
        }
    }

    private static LedgerFault refusedMerge(RowKey key, Op first, Op then) {
        return new LedgerFault("one unit cannot " + then + " the row " + key.entity() + " " + key.id() + " of " + key.tier() + " after it " + first + "d it");
    }

    // ── the commit: ONE record ──────────────────────────────────────────────

    /** The rows staged, in the order first staged. */
    public int size() { return staged.size(); }

    public boolean isEmpty() { return staged.isEmpty(); }

    /** The tiers this unit writes, in the order first staged. */
    public List<String> tiers() { return staged.values().stream().map(s -> s.tier).distinct().toList(); }

    /**
     * Every staged row as ONE {@code execCrud(WALEntryBatch)}: one WAL record, all tiers or none. An empty unit writes nothing. A refusal —
     * a row the cache does not hold, one it already holds, a lock timeout, a WAL that would not append — is a {@link LedgerFault} naming
     * {@code what} and the ledger's words; nothing changed in any tier.
     */
    public void commit(String what) {
        if (committed) throw new IllegalStateException("this unit was committed already: " + what);
        committed = true;
        if (staged.isEmpty()) { release(); return; }
        WALEntryBatch record = asOneRecord();
        EntityResponse answer = busyIsAFaultOfItsOwn(what, () -> written(record));
        if (answer == null || !answer.isSuccess()) {
            throw new LedgerFault("the ledger refused to " + what + " (" + staged.size() + " row(s) in " + tiers() + "), nothing changed: "
                + (answer == null ? "no answer" : answer.getMessage()));
        }
    }

    /** ARCH-0118 S2 · With the rows as read when the ledger checks them; else the one {@code execCrud(batch)}, as always. */
    private EntityResponse written(WALEntryBatch record) {
        if (hold != null) return writtenUnderTheHold(record);
        if (ledger instanceof ExpectingLedger expecting && expecting.checksExpectations()) return expecting.execCrud(record, List.copyOf(firstRead.values()));
        return ledger.execCrud(record);
    }

    /** ARCH-0119 · The batch under the unit's hold; the hold ends with the answer — and is given back when no answer came. */
    private EntityResponse writtenUnderTheHold(WALEntryBatch record) {
        LeasingLedger.Held mine = hold;
        hold = null;
        try {
            return leasing.execCrud(mine, record, List.copyOf(firstRead.values()));
        } catch (RowsBusy lost) {
            throw lost;                                                           // the ledger said the hold is gone: nothing to give back
        } catch (RuntimeException noAnswer) {
            leasing.release(mine);
            throw noAnswer;
        }
    }

    private WALEntryBatch asOneRecord() {
        List<WALEntry> entries = new ArrayList<>(staged.size());
        for (Staged row : staged.values()) entries.add(entryOf(row));
        return new WALEntryBatch("SEED_" + UUID.randomUUID(), entries);
    }

    private static WALEntry entryOf(Staged row) {
        WALEntry entry = new WALEntry();
        entry.setDbName(row.tier);
        entry.setEntityType(row.entity);
        entry.setOperationType(WALEntry.OperationType.valueOf(row.op.name()));
        entry.setEntityId(row.id);
        entry.setEntityData(row.image);
        return entry;
    }

    private static RowKey keyOf(String tier, String entity, Object id) { return new RowKey(tier, entity, String.valueOf(id)); }
}
