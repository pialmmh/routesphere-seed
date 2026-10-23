package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.spi.LiveTrees;
import com.telcobright.seed.sessionflow.spi.TenantLookup;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** A test's live trees: prime-context's trees by root (swappable), the served roots, every serve and reload written down. */
final class FakeLiveTrees implements LiveTrees {

    final Map<String, Tenant> prime = new ConcurrentHashMap<>();
    final Set<String> served = new LinkedHashSet<>();
    final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> versions = new ConcurrentHashMap<>();
    private volatile TenantLookup lookup = TenantLookup.of();

    FakeLiveTrees(Map<String, Tenant> prime, List<String> roots) {
        this.prime.putAll(prime);
        roots.forEach(this::serve);
    }

    @Override public synchronized Set<String> roots() { return Set.copyOf(served); }

    @Override
    public synchronized void serve(String root) {
        if (!prime.containsKey(root)) throw new IllegalStateException("prime-context has no tree of '" + root + "'");
        if (!served.add(root)) return;
        calls.add("serve " + root);
        rebuild();
    }

    @Override
    public synchronized void reload(String root) {
        calls.add("reload " + root);
        versions.merge(root, 1, Integer::sum);
        rebuild();
    }

    private void rebuild() { lookup = TenantLookup.of(served.stream().map(prime::get).toArray(Tenant[]::new)); }

    @Override public Optional<Tenant> root(String rootDbName) { return lookup.root(rootDbName); }
    @Override public Optional<Tenant> tenantOfPartner(String rootDbName, int partnerId) { return lookup.tenantOfPartner(rootDbName, partnerId); }
    @Override public Optional<Tenant> tenantByDbName(String rootDbName, String dbName) { return lookup.tenantByDbName(rootDbName, dbName); }
    @Override public Optional<Tenant> rootOfCode(String code) { return lookup.rootOfCode(code); }

    @Override
    public Optional<RootTree> snapshot(String root) {
        return lookup.snapshot(root).map(t -> RootTree.of(t.root(), versions.getOrDefault(root, 0), t.tree()));
    }
}
