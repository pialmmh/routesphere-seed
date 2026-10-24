package com.telcobright.seed.sessionflow.internal;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.spi.TreeGate;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1-G · A root's new tree shows no tier its ledger lacks. Whatever reloads the tree — the {@code tenant_created} event, the doorbell
 * {@code config_event_loader_<root>} (a reseller's provisioning rings it AND sends the event; the ring can come first), a root served live —
 * every tier the tree holds that its root's ledger was not built over and was not handed yet is handed to that ledger FIRST
 * ({@code LedgerPort.tierAdded}), once (the event and the ring together: one hand). A ledger that throws keeps the tree from being shown
 * (the last tree stays, the next load tries again); a ledger that says its own failure (the switch ledger without {@code addDatabase})
 * lets the tree be shown, the tier's money refused until it loads. A root whose roads are not made yet passes: no session reaches it, and
 * its ledger is built over the tree it is made with.
 */
public final class TiersFirst implements TreeGate {

    private final PerRootLedger ledger;
    private final Set<String> builtOver = ConcurrentHashMap.newKeySet();
    private final Set<String> handed = ConcurrentHashMap.newKeySet();

    public TiersFirst(PerRootLedger ledger) { this.ledger = ledger; }

    @Override
    public void pass(String root, Tenant tree) {
        String rootDb = tree.getDbName();
        if (ledger.of(rootDb).isEmpty()) return;
        for (String tierDb : tiersOf(tree)) {
            if (!builtOver.contains(key(rootDb, tierDb))) hand(rootDb, tierDb);
        }
    }

    /**
     * One tier to its root's ledger, once — the event's road (it names a tier created now: handed even when the ledger was built over it,
     * the ledger's own "nothing") and the trees' gate both come here.
     */
    public synchronized void hand(String rootDb, String tierDb) {
        if (tierDb == null || handed.contains(key(rootDb, tierDb))) return;
        ledger.tierAdded(rootDb, tierDb);
        handed.add(key(rootDb, tierDb));
    }

    /** The tiers a root's ledger was built over (its roads made with this tree): the gate never hands them. */
    public void builtOver(String rootDb, Tenant tree) {
        for (String tierDb : tiersOf(tree)) builtOver.add(key(rootDb, tierDb));
    }

    /** The root and every reseller tier under it — the schemas the switch ledger is built over. */
    static Set<String> tiersOf(Tenant tree) {
        Set<String> tiers = new LinkedHashSet<>();
        tiers.add(tree.getDbName());
        if (tree.getTenantIndex() != null) for (Tenant tier : tree.getTenantIndex().values()) tiers.add(tier.getDbName());
        return tiers;
    }

    private static String key(String rootDb, String tierDb) { return rootDb + "/" + tierDb; }
}
