package com.telcobright.seed.tenant.internal;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.spi.TreeGate;

import java.util.Objects;

/**
 * P1-G · The gate the kit gives AFTER the trees are made (a root's ledger is built over its first tree, so the road comes after the trees):
 * every load of a tree asks it before the tree is swapped in; until the road gives its gate, the gate is open.
 */
public final class TreeGateHolder implements TreeGate {

    private volatile TreeGate gate = TreeGate.OPEN;

    public void give(TreeGate gate) { this.gate = Objects.requireNonNull(gate, "gate"); }

    @Override public void pass(String root, Tenant tree) { gate.pass(root, tree); }
}
