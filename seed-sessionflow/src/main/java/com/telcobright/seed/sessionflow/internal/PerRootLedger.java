package com.telcobright.seed.sessionflow.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The ONE ledger port of a kit made from the bootstrap: each served root's own ledger (its {@code ledger.kind}), every tier's money on the
 * ledger of the root its tier belongs to — never another root's (a partner id is unique inside one tree only). A tier whose root has no
 * ledger is a {@code LedgerFault} (BILLING_SYSTEM_ERROR, never a balance cause).
 */
public final class PerRootLedger implements LedgerPort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PerRootLedger.class);

    private final Map<String, LedgerPort> byRoot = new ConcurrentHashMap<>();
    private final Function<Tenant, String> rootOf;

    /** @param rootOf the root (database name) a tier's tenant belongs to */
    public PerRootLedger(Function<Tenant, String> rootOf) { this.rootOf = rootOf; }

    public void add(String root, LedgerPort ledger) { byRoot.put(root, ledger); }

    public Optional<LedgerPort> of(String root) { return Optional.ofNullable(byRoot.get(root)); }

    /** P1-H · A root's ledger taken out again (its roads could not all be made); the caller closes it. */
    public LedgerPort remove(String root) { return byRoot.remove(root); }

    @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { return ledgerOf(level).reserve(level, amount, reference); }

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        return ledgerOf(level).reserve(level, amount, reference, withinMs);
    }

    @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return ledgerOf(level).settle(level, charged); }

    @Override public void release(LevelAdmission level, String why) { ledgerOf(level).release(level, why); }

    @Override public Optional<BigDecimal> balanceOf(LevelAdmission level) { return ledgerOf(level).balanceOf(level); }

    @Override public Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) { return ledgerOf(level).charge(level, amount, reference); }

    @Override public TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) { return ledgerOf(level).refund(level, amount, why); }

    @Override public Credited credit(LevelAdmission account, BigDecimal amount, String reference) { return ledgerOf(account).credit(account, amount, reference); }

    @Override public Credited reparent(LevelAdmission account, long purchaseId) { return ledgerOf(account).reparent(account, purchaseId); }

    /** L2b · A unit on the ledger of the root its first tier belongs to; a tier of another root in the same unit is refused in words. */
    @Override public LedgerUnit unit() { return new RootUnit(); }

    @Override public long slowestAnswerMs() { return byRoot.values().stream().mapToLong(LedgerPort::slowestAnswerMs).max().orElse(0); }

    private LedgerPort ledgerOf(LevelAdmission level) {
        String root = level.getTenant() == null ? null : rootOf.apply(level.getTenant());
        LedgerPort ledger = root == null ? null : byRoot.get(root);
        if (ledger == null) throw new LedgerFault("no ledger serves the root '" + root + "' of tier " + level.getLevelIndex() + " (roots " + byRoot.keySet() + ")");
        return ledger;
    }

    /** A tier created live under {@code root}: that root's ledger alone loads it (item 13); a root without a ledger here is nothing. */
    public void tierAdded(String root, String tierDb) {
        LedgerPort ledger = root == null ? null : byRoot.get(root);
        if (ledger != null && tierDb != null) ledger.tierAdded(tierDb);
    }

    /** Are the tier's books loaded on its root's ledger? A root without a ledger here: true (nothing to load, nothing to hand again). */
    public boolean tierLoaded(String root, String tierDb) {
        LedgerPort ledger = root == null ? null : byRoot.get(root);
        return ledger == null || tierDb == null || ledger.tierLoaded(tierDb);
    }

    /** P1-J · A root's tree was reloaded: that root's ledger alone is told (it merges the rows written since); a root without one is nothing. */
    public void treeReloaded(String root) {
        LedgerPort ledger = root == null ? null : byRoot.get(root);
        if (ledger != null) ledger.treeReloaded();
    }

    /** The engine's close: every root's ledger that holds something open (a switch ledger's MemLedger) is closed; a failure is said, the rest go on. */
    @Override
    public void close() {
        byRoot.forEach((root, ledger) -> {
            if (!(ledger instanceof AutoCloseable closing)) return;
            try {
                closing.close();
            } catch (Exception e) {
                log.warn("the ledger of root {} did not close: {}", root, e.toString());
            }
        });
    }

    /** One unit = one root's ledger (a session's tiers are all of its own root): opened on the first tier it is asked about. */
    private final class RootUnit implements LedgerUnit {
        private String root;
        private LedgerUnit unit;

        private LedgerUnit of(LevelAdmission level) {
            String rootOfLevel = level.getTenant() == null ? null : rootOf.apply(level.getTenant());
            if (unit == null) {
                unit = ledgerOf(level).unit();
                root = rootOfLevel;
            } else if (root != null && !root.equals(rootOfLevel)) {
                throw new LedgerFault("one unit spans one root's ledger: tier " + level.getLevelIndex() + " is of root '" + rootOfLevel + "', the unit of '" + root + "'");
            }
            return unit;
        }

        @Override
        public void lock(Collection<LevelAdmission> accounts) {
            for (LevelAdmission account : accounts) of(account);
            if (unit != null) unit.lock(accounts);
        }
        @Override public Optional<BigDecimal> balanceOf(LevelAdmission level) { return of(level).balanceOf(level); }
        @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) { return of(level).reserve(level, amount, reference, withinMs); }
        @Override public Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) { return of(level).charge(level, amount, reference); }
        @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return of(level).settle(level, charged); }
        @Override public void release(LevelAdmission level, String why) { of(level).release(level, why); }
        @Override public TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) { return of(level).refund(level, amount, why); }
        @Override public void commit(String what) { if (unit != null) unit.commit(what); }
        @Override public void close() { if (unit != null) unit.close(); }
    }

    /** The root of a tier's tenant by its own tree: the last of its ancestor chain; a tenant with no chain is its own root. */
    public static String rootByTheChain(Tenant tenant) {
        List<Tenant> chain = tenant.getAncestorChain();
        return chain == null || chain.isEmpty() ? tenant.getDbName() : chain.get(chain.size() - 1).getDbName();
    }
}
