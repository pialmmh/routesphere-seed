package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.api.LiveBalance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The switch ledger's port over a MemLedger the {@code SwitchLedgerMaker} opened for one root. Every verb is the port's, run through a gate:
 * a verb holds it shared, a NEW TIER's load holds it alone — so a tier created live (R10, item 13) is loaded between the root's money
 * moves, never under a session's reserve. The same tier twice is one load; a tier the ledger was built over is none. P1-G: after the
 * tier's schema, the rows it brought beside it (its own account at its parent) are merged too ({@code NewRowsOfATier}). Closing the port shuts
 * the MemLedger down (its WAL consumer stops) — the bootstrap road closes it with the engine.
 */
public final class OwnedMemLedgerPort implements LedgerPort, LiveBalance, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OwnedMemLedgerPort.class);

    private final MemLedgerPort port;
    private final MemLedger memLedger;
    private final Predicate<String> loader;
    private final Set<String> tiers = ConcurrentHashMap.newKeySet();
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();

    /**
     * @param tiers  the tier schemas the MemLedger was built over
     * @param loader loads ONE new tier schema into the MemLedger (true = loaded now): {@link AddTierLive}
     */
    public OwnedMemLedgerPort(MemLedgerPort port, MemLedger memLedger, Collection<String> tiers, Predicate<String> loader) {
        this.port = port;
        this.memLedger = memLedger;
        this.loader = loader;
        this.tiers.addAll(tiers);
    }

    @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { return shared(() -> port.reserve(level, amount, reference)); }

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        return shared(() -> port.reserve(level, amount, reference, withinMs));
    }

    @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return shared(() -> port.settle(level, charged)); }

    @Override public void release(LevelAdmission level, String why) { shared(() -> { port.release(level, why); return null; }); }

    @Override public long slowestAnswerMs() { return port.slowestAnswerMs(); }

    @Override public Optional<BigDecimal> balanceOf(LevelAdmission level) { return shared(() -> port.balanceOf(level)); }

    /**
     * A reseller tier created live under this root: its schema loaded ONCE, with no money move of this root running meanwhile. A load that
     * fails is said once (the tier's money is refused until a later word or the next start loads it) — the root's other tiers go on.
     */
    @Override
    public void tierAdded(String tierDb) {
        if (tierDb == null || tiers.contains(tierDb)) return;
        gate.writeLock().lock();
        try {
            if (!tiers.contains(tierDb)) loadAlone(tierDb);
        } finally {
            gate.writeLock().unlock();
        }
    }

    private void loadAlone(String tierDb) {
        try {
            loader.test(tierDb);
            tiers.add(tierDb);
            log.info("the switch ledger loaded the tier schema {} live (between the root's money moves)", tierDb);
        } catch (RuntimeException e) {
            log.error("the switch ledger did NOT load the tier schema {} live: {}", tierDb, e.getMessage());
            return;
        }
        mergeTheRowsBeside(tierDb);
    }

    /** P1-G · The tier's rows outside its own schema — its own account at its parent — merged too, no settled reserve brought back. */
    private void mergeTheRowsBeside(String tierDb) {
        try {
            int rows = NewRowsOfATier.merge(memLedger);
            log.info("the switch ledger merged {} new row(s) beside the tier {} (its own account at its parent among them)", rows, tierDb);
        } catch (RuntimeException e) {
            log.error("the switch ledger did NOT merge the new rows beside the tier {} — its account at its parent stays unknown until the next start: {}", tierDb, e.getMessage());
        }
    }

    /** The MemLedger shut down, once (its own flag makes a second close nothing). */
    @Override public void close() { memLedger.shutdown(); }

    private <T> T shared(Supplier<T> verb) {
        gate.readLock().lock();
        try {
            return verb.get();
        } finally {
            gate.readLock().unlock();
        }
    }
}
