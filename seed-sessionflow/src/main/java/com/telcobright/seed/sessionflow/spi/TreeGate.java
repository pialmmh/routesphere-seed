package com.telcobright.seed.sessionflow.spi;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;

/**
 * P1-G · The step every NEW tree of a root takes before a session can see it. The bootstrap road's gate hands the tree's tiers to the
 * root's ledger first ({@code LedgerPort.tierAdded}): a reseller's provisioning rings the doorbell AND sends {@code tenant_created}, and
 * whichever reloads the tree first, the tree never shows a tier its ledger has not been handed. A gate that throws keeps the tree from
 * being shown (the last tree stays; the next load tries again).
 */
@FunctionalInterface
public interface TreeGate {

    /** No step: every new tree is shown as it is (until the road gives its gate). */
    TreeGate OPEN = (root, tree) -> { };

    /** A root's new tree, loaded and not shown yet. */
    void pass(String root, Tenant tree);
}
