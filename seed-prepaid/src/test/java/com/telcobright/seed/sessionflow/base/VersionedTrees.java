package com.telcobright.seed.sessionflow.base;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.api.RootTreeInstaller;
import com.telcobright.seed.sessionflow.spi.TenantLookup;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The lab's trees per root with versions, swapped whole as the doorbell's reload swaps them (seed-tenant's TenantTrees in miniature). */
final class VersionedTrees implements TenantLookup {

    private final Map<String, RootTree> byRoot = new ConcurrentHashMap<>();
    private final RootTreeInstaller installer = new RootTreeInstaller();

    /** A load of the root's tree: installed with its index, once (P1-O). */
    VersionedTrees serve(Tenant root) {
        RootTree before = byRoot.get(root.getDbName());
        byRoot.put(root.getDbName(), installer.install(root.getDbName(), before == null ? 1 : before.version() + 1, root));
        return this;
    }

    @Override public long rootIndexBuilds() { return installer.rootIndexBuilds(); }

    @Override public Optional<RootTree> snapshot(String root) { return Optional.ofNullable(root == null ? null : byRoot.get(root)); }

    @Override public Optional<Tenant> root(String rootDbName) { return snapshot(rootDbName).map(RootTree::tree); }

    @Override public Optional<Tenant> tenantOfPartner(String rootDbName, int partnerId) {
        return snapshot(rootDbName).map(t -> t.entryOf(partnerId)).map(e -> e.tenant());
    }

    @Override public Optional<Tenant> tenantByDbName(String rootDbName, String dbName) { return snapshot(rootDbName).flatMap(t -> t.tier(dbName)); }

    @Override public Optional<Tenant> rootOfCode(String code) { return root(code); }
}
