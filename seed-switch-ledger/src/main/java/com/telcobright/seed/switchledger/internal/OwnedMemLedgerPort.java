package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;
import com.telcobright.seed.switchledger.api.Doorbell;
import com.telcobright.seed.switchledger.api.LedgerDb;
import com.telcobright.seed.switchledger.api.LiveBalance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
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
    /** P1-J · The default spacing of two merges of the root's new rows. */
    public static final long DEFAULT_MERGE_SPACING_MS = 5_000;
    private final CoalescedMerges merges;
    /** L2b item 3 · The root's ledger DB (its tables, its signals); null = the two tables, every tree's reload merges (before L2b). */
    private final LedgerDb ledgerDb;

    /**
     * @param tiers  the tier schemas the MemLedger was built over
     * @param loader loads ONE new tier schema into the MemLedger (true = loaded now): {@link AddTierLive}
     */
    public OwnedMemLedgerPort(MemLedgerPort port, MemLedger memLedger, Collection<String> tiers, Predicate<String> loader) {
        this(port, memLedger, tiers, loader, DEFAULT_MERGE_SPACING_MS);
    }

    /** P1-J · The same, two merges of the root's new rows at least {@code mergeSpacingMs} apart ({@code ledger.merge-spacing-sec}). */
    public OwnedMemLedgerPort(MemLedgerPort port, MemLedger memLedger, Collection<String> tiers, Predicate<String> loader, long mergeSpacingMs) {
        this(port, memLedger, tiers, loader, mergeSpacingMs, null);
    }

    /**
     * L2b item 3 · The same over a ledger DB: its doorbell's rings decide (an INSERT on a cached table → a reload of new rows, coalesced; a
     * catch-up word → the same; a tier it lacks → loaded); a tree's reload alone merges nothing (S2: a ring of a rule or a campaign no
     * longer re-reads every cached table of every tier).
     */
    public OwnedMemLedgerPort(MemLedgerPort port, MemLedger memLedger, Collection<String> tiers, Predicate<String> loader, long mergeSpacingMs, LedgerDb ledgerDb) {
        this.ledgerDb = ledgerDb;
        this.port = port;
        this.memLedger = memLedger;
        this.loader = loader;
        this.tiers.addAll(tiers);
        this.merges = new CoalescedMerges(mergeSpacingMs, this::mergeTheNewRowsAlone, System::currentTimeMillis, "switch-ledger");
    }

    @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { return shared(() -> port.reserve(level, amount, reference)); }

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        return shared(() -> port.reserve(level, amount, reference, withinMs));
    }

    @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return shared(() -> port.settle(level, charged)); }

    @Override public void release(LevelAdmission level, String why) { shared(() -> { port.release(level, why); return null; }); }

    /** L2a's verbs, through the gate too (L2b: they were not delegated — a CHARGE_NOW lane or a credit road on the bootstrap kit was refused). */
    @Override public Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) { return shared(() -> port.charge(level, amount, reference)); }

    @Override public TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) { return shared(() -> port.refund(level, amount, why)); }

    @Override public Credited credit(LevelAdmission account, BigDecimal amount, String reference) { return shared(() -> port.credit(account, amount, reference)); }

    @Override public Credited reparent(LevelAdmission account, long purchaseId) { return shared(() -> port.reparent(account, purchaseId)); }

    /** L2b · A unit holds the gate shared from its opening to its close: a new tier never loads under a unit's locks. */
    @Override
    public LedgerUnit unit() {
        gate.readLock().lock();
        try {
            return new GatedUnit(port.unit(), gate.readLock()::unlock);
        } catch (RuntimeException e) {
            gate.readLock().unlock();
            throw e;
        }
    }

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

    /** The tier's schema is in the MemLedger — built over at the start, or loaded live; false after a load that was said, so the gate hands it again. */
    @Override public boolean tierLoaded(String tierDb) { return tierDb != null && tiers.contains(tierDb); }

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

    /**
     * P1-J · The root's tree was reloaded — the doorbell's ring (a purchase wrote accounts), a {@code tenant_created}, a root served, the
     * backstop: the rows written since the ledger last looked (an account made AFTER its tier existed) are merged — at most one merge per
     * spacing, the first at once on the reloading thread, the rest of a burst ONE merge at the end of the spacing; under the write gate
     * (the root's money moves wait for it, never run under it), ghost reserves taken out ({@code NewRowsOfATier}).
     */
    @Override public void treeReloaded() { if (ledgerDb == null) merges.ask(); }

    /** L2b item 3 · A ring of the root's doorbell, read by its ledger DB: the tiers it lacks loaded, then (if asked) one coalesced reload. */
    @Override
    public void doorbell(String what, List<String> changes) {
        if (ledgerDb == null) return;
        LedgerDb.Decision asked = ledgerDb.decide(new Doorbell(what, changes), Set.copyOf(tiers));
        asked.tiersToAdd().forEach(this::tierAdded);
        if (asked.reload()) merges.ask();
    }

    /** The merges of the root's new rows run so far (the operations' view; a test's). */
    public long mergesRun() { return merges.runs(); }

    /** The MemLedger shut down, once (its own flag makes a second close nothing); the merges stopped first. */
    @Override
    public void close() {
        merges.close();
        memLedger.shutdown();
    }

    private void mergeTheNewRowsAlone() {
        gate.writeLock().lock();
        try {
            int rows = NewRowsOfATier.merge(memLedger);
            if (rows > 0) log.info("the switch ledger merged {} new row(s) after its tree was reloaded (an account written after its tier)", rows);
        } catch (RuntimeException e) {
            log.error("the switch ledger did NOT merge the rows written since its last look (its tree was reloaded): {}", e.getMessage());
        } finally {
            gate.writeLock().unlock();
        }
    }

    private <T> T shared(Supplier<T> verb) {
        gate.readLock().lock();
        try {
            return verb.get();
        } finally {
            gate.readLock().unlock();
        }
    }
}
