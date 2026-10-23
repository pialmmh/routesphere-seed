package com.telcobright.seed.sessionflow.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
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

    @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { return ledgerOf(level).reserve(level, amount, reference); }

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        return ledgerOf(level).reserve(level, amount, reference, withinMs);
    }

    @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return ledgerOf(level).settle(level, charged); }

    @Override public void release(LevelAdmission level, String why) { ledgerOf(level).release(level, why); }

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

    /** The root of a tier's tenant by its own tree: the last of its ancestor chain; a tenant with no chain is its own root. */
    public static String rootByTheChain(Tenant tenant) {
        List<Tenant> chain = tenant.getAncestorChain();
        return chain == null || chain.isEmpty() ? tenant.getDbName() : chain.get(chain.size() - 1).getDbName();
    }
}
