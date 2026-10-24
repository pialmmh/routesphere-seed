package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.api.RootTreeInstaller;
import com.telcobright.seed.sessionflow.spi.LiveTrees;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.spi.TreeGate;

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
    private volatile TreeGate gate = TreeGate.OPEN;
    private final RootTreeInstaller installer = new RootTreeInstaller();
    private final Map<String, RootTree> installed = new ConcurrentHashMap<>();

    FakeLiveTrees(Map<String, Tenant> prime, List<String> roots) {
        this.prime.putAll(prime);
        roots.forEach(this::serve);
    }

    @Override public synchronized Set<String> roots() { return Set.copyOf(served); }

    @Override
    public synchronized void serve(String root) {
        if (!prime.containsKey(root)) throw new IllegalStateException("prime-context has no tree of '" + root + "'");
        if (served.contains(root)) return;
        gate.pass(root, prime.get(root));
        served.add(root);
        calls.add("serve " + root);
        rebuild();
    }

    /** P1-G: every new tree through the road's gate before it is shown; a gate that throws keeps the last tree. */
    @Override
    public synchronized void reload(String root) {
        calls.add("reload " + root);
        gate.pass(root, prime.get(root));
        versions.merge(root, 1, Integer::sum);
        rebuild();
    }

    @Override public void gateEachNewTree(TreeGate gate) { this.gate = gate; }

    /** P1-H: a root whose roads could not be made, taken out again. */
    @Override
    public synchronized void unserve(String root) {
        if (!served.remove(root)) return;
        calls.add("unserve " + root);
        rebuild();
    }

    /** P1-O: every served root installed once per serve or reload, with its index — a session never builds one. */
    private void rebuild() {
        lookup = TenantLookup.of(served.stream().map(prime::get).toArray(Tenant[]::new));
        installed.keySet().retainAll(served);
        for (String root : served) {
            RootTree last = installed.get(root);
            int version = versions.getOrDefault(root, 0);
            if (last == null || last.version() != version || last.tree() != prime.get(root)) installed.put(root, installer.install(root, version, prime.get(root)));
        }
    }

    @Override public Optional<Tenant> root(String rootDbName) { return lookup.root(rootDbName); }
    @Override public Optional<Tenant> tenantOfPartner(String rootDbName, int partnerId) { return lookup.tenantOfPartner(rootDbName, partnerId); }
    @Override public Optional<Tenant> tenantByDbName(String rootDbName, String dbName) { return lookup.tenantByDbName(rootDbName, dbName); }
    @Override public Optional<Tenant> rootOfCode(String code) { return lookup.rootOfCode(code); }

    @Override
    public Optional<RootTree> snapshot(String root) { return root == null ? Optional.empty() : Optional.ofNullable(installed.get(root)); }

    @Override public long rootIndexBuilds() { return installer.rootIndexBuilds(); }
}
