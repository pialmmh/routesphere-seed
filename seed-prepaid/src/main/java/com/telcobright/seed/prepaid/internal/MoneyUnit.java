package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * L2b item 2 · The ONE unit of the ledger an engine verb runs in — every tier's hold at admission ({@code holdTheWalk}), every tier's
 * renewal ({@code renewWindow}), every tier's settlement ({@code finish}), a refused candidate's give-back ({@code release}): opened on the
 * session's thread, its accounts locked once in the global order, every move the moved steps ask staged in it ({@link #moves}), written by
 * {@link #commit} as ONE (all tiers or none), and only THEN what the moves recorded on the session (a tier's reserve facts, the journal of
 * the sessions in the air, the walk) is applied ({@link #afterTheRecord}). Closed without its commit: nothing written, nothing recorded.
 *
 * <p>The moved steps ask {@link #moves(LedgerPort)} for their ledger: the current unit while one is open on the thread, else the port
 * itself (a move of its own — the restart's leftovers, the admin roads).
 */
final class MoneyUnit implements AutoCloseable {

    private static final ThreadLocal<MoneyUnit> CURRENT = new ThreadLocal<>();

    private final LedgerUnit unit;
    private final List<Runnable> afterTheRecord = new ArrayList<>();
    /** A verb asked inside another verb's unit JOINS it: its commit and its close are the outer verb's. */
    private final MoneyUnit outer;
    private boolean committed;

    private MoneyUnit(LedgerUnit unit, MoneyUnit outer) {
        this.unit = unit;
        this.outer = outer;
    }

    /** A new unit over the kit's ledger, current on this thread until closed; inside an open one, that one (joined). */
    static MoneyUnit open(LedgerPort port, Collection<LevelAdmission> accounts) {
        MoneyUnit current = CURRENT.get();
        if (current != null) {
            current.unit.lock(accounts);
            return new MoneyUnit(current.unit, current);
        }
        MoneyUnit opened = new MoneyUnit(port.unit(), null);
        CURRENT.set(opened);
        try {
            opened.unit.lock(accounts);
            return opened;
        } catch (RuntimeException e) {
            opened.close();
            throw e;
        }
    }

    /** The ledger a moved step moves money on: the open unit, else the port (one move, written at once). */
    static LedgerUnit moves(LedgerPort port) {
        MoneyUnit current = CURRENT.get();
        return current != null ? current.unit : new PortMoves(port);
    }

    /** What a move records on the session: after the unit's record, when a unit is open; at once otherwise. */
    static void afterTheRecord(Runnable record) {
        MoneyUnit current = CURRENT.get();
        if (current != null) current.afterTheRecord.add(record);
        else record.run();
    }

    /** ONE write for every move staged; then what they recorded. A refusal is the ledger's fault: nothing written, nothing recorded. */
    void commit(String what) {
        if (outer != null) { committed = true; return; }
        unit.commit(what);
        committed = true;
        List<Runnable> records = List.copyOf(afterTheRecord);
        afterTheRecord.clear();
        records.forEach(Runnable::run);
    }

    boolean committed() { return committed; }

    /** The operation was refused with {@code why}: a ledger that applied its moves at once gives them back with this cause at the close. */
    void abandon(String why) { if (outer == null) unit.abandon(why); }

    @Override
    public void close() {
        if (outer != null) return;
        CURRENT.remove();
        afterTheRecord.clear();
        unit.close();
    }

    /** The port as a unit of single moves: each move is the port's own verb, written at once (the port makes each one record). */
    private record PortMoves(LedgerPort port) implements LedgerUnit {
        @Override public void lock(Collection<LevelAdmission> accounts) { }
        @Override public Optional<BigDecimal> balanceOf(LevelAdmission level) { return port.balanceOf(level); }
        @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) { return port.reserve(level, amount, reference, withinMs); }
        @Override public Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) { return port.charge(level, amount, reference); }
        @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return port.settle(level, charged); }
        @Override public void release(LevelAdmission level, String why) { port.release(level, why); }
        @Override public TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) { return port.refund(level, amount, why); }
        @Override public void commit(String what) { }
        @Override public void close() { }
    }
}
