package com.telcobright.seed.tenant.internal;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.spi.LiveTrees;
import com.telcobright.seed.tenant.api.TenantTrees;

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

    private LiveTenantTrees(TenantTrees trees, Served served) {
        this.trees = trees;
        this.served = served;
    }

    /** The trees started — every served root loaded — or refused naming the roots that could not be. */
    public static LiveTenantTrees started(TenantTrees trees, Served served) {
        LiveTenantTrees live = new LiveTenantTrees(trees.start(), served);
        for (String root : served.active()) live.refuseUnloaded(root);
        return live;
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

    /** P1-G · A root whose tree could not be loaded leaves the directory again: nothing of it stays served, the next serve loads it. */
    private void unserve(String root) {
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

    @Override public void close() { trees.close(); }
}
