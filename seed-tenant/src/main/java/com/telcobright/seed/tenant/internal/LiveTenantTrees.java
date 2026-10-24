package com.telcobright.seed.tenant.internal;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.spi.LiveTrees;
import com.telcobright.seed.sessionflow.spi.TreeGate;
import com.telcobright.seed.tenant.api.TenantTrees;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * seed-tenant's trees as the kit's live trees (R10): the served roots are a directory this class holds — a root added live joins it, the
 * trees load it, and the kit serves it; a tier added under a root reloads that root. A root whose tree cannot be loaded is refused in words
 * — and, P1-G, leaves nothing served: it is taken out of the directory again (prime-context's tree road answers 404 ROOT_NOT_SERVED for a
 * root it announced but does not serve yet), so the next serve loads it.
 */
public final class LiveTenantTrees implements LiveTrees {

    private static final Logger log = LoggerFactory.getLogger(LiveTenantTrees.class);

    static final long LOAD_WAIT_SEC = 30;

    /** The roots served: the trees' directory, grown when a root is added. */
    public static final class Served {
        private final Set<String> roots;
        public Served(Collection<String> roots) { this.roots = new LinkedHashSet<>(roots); }
        public synchronized Set<String> active() { return Set.copyOf(roots); }
        synchronized boolean add(String root) { return roots.add(root); }
        synchronized boolean remove(String root) { return roots.remove(root); }
    }

    private final TenantTrees trees;
    private final Served served;
    private final TreeGateHolder gate;

    private LiveTenantTrees(TenantTrees trees, Served served, TreeGateHolder gate) {
        this.trees = trees;
        this.served = served;
        this.gate = gate;
    }

    /** The trees started — every served root loaded — or refused naming the roots that could not be. Trees built with no gate: never gated. */
    public static LiveTenantTrees started(TenantTrees trees, Served served) { return started(trees, served, null); }

    /** P1-G · The same, the trees built with {@code gate} ({@code TenantTreesBuilder.gate}): the road's gate reaches every load through it. */
    public static LiveTenantTrees started(TenantTrees trees, Served served, TreeGateHolder gate) {
        LiveTenantTrees live = new LiveTenantTrees(trees.start(), served, gate);
        for (String root : served.active()) live.refuseUnloaded(root);
        return live;
    }

    /** P1-G · The road's gate on every new tree, before it is swapped in (the doorbell's ring, a reload, a root served). */
    @Override
    public void gateEachNewTree(TreeGate gate) {
        if (this.gate == null) { log.warn("these trees were built with no gate holder (TenantTreesBuilder.gate): a new tree is shown without the kit's step"); return; }
        this.gate.give(gate);
    }

    @Override public Set<String> roots() { return served.active(); }

    @Override
    public void serve(String root) {
        if (!served.add(root)) return;
        try {
            trees.refreshDirectory();
            reload(root);
        } catch (RuntimeException notServedYet) {
            unserve(root);
            throw notServedYet;
        }
    }

    /** P1-G · A root whose tree could not be loaded — P1-H, or whose roads could not be made — leaves the directory again: nothing of it stays served. */
    @Override
    public void unserve(String root) {
        served.remove(root);
        trees.refreshDirectory();
    }

    @Override
    public void reload(String root) {
        try {
            trees.reload(root, "tenant_created").get(LOAD_WAIT_SEC, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("the tree of the root '" + root + "' could not be loaded from prime-context: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the tree of '" + root + "' loaded", e);
        }
    }

    private void refuseUnloaded(String root) {
        if (trees.find(root).isEmpty()) throw new IllegalStateException("the served root '" + root + "' has no tree: prime-context's tree road did not load it — the root would have no tree");
    }

    // ── the base's lookups, each inside the root it names ──

    @Override public Optional<Tenant> root(String rootDbName) { return trees.root(rootDbName); }
    @Override public Optional<Tenant> tenantOfPartner(String rootDbName, int partnerId) { return trees.tenantOfPartner(rootDbName, partnerId); }
    @Override public Optional<Tenant> tenantByDbName(String rootDbName, String dbName) { return trees.tenantByDbName(rootDbName, dbName); }
    @Override public Optional<Tenant> rootOfCode(String code) { return trees.rootOfCode(code); }
    @Override public Optional<RootTree> snapshot(String root) { return trees.snapshot(root); }
    @Override public long rootIndexBuilds() { return trees.rootIndexBuilds(); }

    @Override public void close() { trees.close(); }
}
