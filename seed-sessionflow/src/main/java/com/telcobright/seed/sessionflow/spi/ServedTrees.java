package com.telcobright.seed.sessionflow.spi;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.EntryPartner;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.api.RootTreeInstaller;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link TenantLookup#of}: the trees a process serves, each installed ONCE at the construction (P1-O: its {@link RootTree} and that tree's
 * index) — no question ever crosses from one tree to another, and none walks a tree: a root by its database name or its code, a partner,
 * a tier, each one map get.
 */
final class ServedTrees implements TenantLookup {

    private final RootTreeInstaller installer = new RootTreeInstaller();
    private final Map<String, RootTree> byRootDb = new LinkedHashMap<>();
    private final Map<String, RootTree> byCode = new HashMap<>();

    ServedTrees(Tenant... roots) { for (Tenant root : roots) install(root); }

    /** One served root: refused when another root has its name or its code; installed with its index. */
    private void install(Tenant root) {
        if (byRootDb.containsKey(root.getDbName())) {
            throw new IllegalArgumentException("two served trees have a root named '" + root.getDbName() + "': a call could not say which one is its own");
        }
        if (root.getName() != null && byCode.containsKey(root.getName())) {
            throw new IllegalArgumentException("two served trees carry the tenant code '" + root.getName() + "': a rule's tenant could not say which root is its own");
        }
        RootTree installed = installer.install(root.getName() != null ? root.getName() : root.getDbName(), 0, root);
        byRootDb.put(root.getDbName(), installed);
        if (root.getName() != null) byCode.put(root.getName(), installed);
    }

    @Override
    public Optional<Tenant> root(String rootDbName) { return treeOf(rootDbName).map(RootTree::tree); }

    @Override
    public Optional<Tenant> tenantOfPartner(String rootDbName, int partnerId) {
        return treeOf(rootDbName).map(tree -> tree.entryOf(partnerId)).map(EntryPartner::tenant);
    }

    @Override
    public Optional<Tenant> tenantByDbName(String rootDbName, String dbName) {
        return dbName == null ? Optional.empty() : treeOf(rootDbName).map(tree -> tree.tree().findTenantByDbName(dbName));
    }

    @Override
    public Optional<Tenant> rootOfCode(String code) { return code == null ? Optional.empty() : Optional.ofNullable(byCode.get(code)).map(RootTree::tree); }

    /** The installed snapshot — by the root's code, else its database name — the same instance for every session. */
    @Override
    public Optional<RootTree> snapshot(String root) {
        if (root == null) return Optional.empty();
        RootTree byItsCode = byCode.get(root);
        return Optional.ofNullable(byItsCode != null ? byItsCode : byRootDb.get(root));
    }

    @Override
    public long rootIndexBuilds() { return installer.rootIndexBuilds(); }

    private Optional<RootTree> treeOf(String rootDbName) {
        return rootDbName == null ? Optional.empty() : Optional.ofNullable(byRootDb.get(rootDbName));
    }
}
