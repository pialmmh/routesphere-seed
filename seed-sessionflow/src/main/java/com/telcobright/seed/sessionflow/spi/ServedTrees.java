package com.telcobright.seed.sessionflow.spi;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link TenantLookup#of}: the trees a process serves, each with its own partner map — no question ever crosses from one tree to another. */
final class ServedTrees implements TenantLookup {

    private final Map<String, Tree> byRoot = new LinkedHashMap<>();

    ServedTrees(Tenant... roots) {
        for (Tenant root : roots) {
            if (byRoot.putIfAbsent(root.getDbName(), new Tree(root)) != null) {
                throw new IllegalArgumentException("two served trees have a root named '" + root.getDbName() + "': a call could not say which one is its own");
            }
        }
    }

    @Override
    public Optional<Tenant> root(String rootDbName) { return treeOf(rootDbName).map(tree -> tree.root); }

    @Override
    public Optional<Tenant> tenantOfPartner(String rootDbName, int partnerId) { return treeOf(rootDbName).map(tree -> tree.ownerOf(partnerId)); }

    @Override
    public Optional<Tenant> tenantByDbName(String rootDbName, String dbName) {
        return dbName == null ? Optional.empty() : treeOf(rootDbName).map(tree -> tree.root.findTenantByDbName(dbName));
    }

    private Optional<Tree> treeOf(String rootDbName) {
        return rootDbName == null ? Optional.empty() : Optional.ofNullable(byRoot.get(rootDbName));
    }

    /** One served tree and its own partner map. The map corrects itself: the walk of THIS tree is the truth, never another tree's. */
    private static final class Tree {
        final Tenant root;
        final Map<Integer, Tenant> ownerOfPartner = new ConcurrentHashMap<>();

        Tree(Tenant root) {
            this.root = root;
            for (Tenant t : root.getTenantIndex().values()) {
                if (t.getContext() == null || t.getContext().getPartners() == null) continue;
                for (Integer id : t.getContext().getPartners().keySet()) ownerOfPartner.putIfAbsent(id, t);
            }
        }

        /** The tenant of this tree that holds the partner, or null. */
        Tenant ownerOf(int partnerId) {
            Tenant known = ownerOfPartner.get(partnerId);
            if (known != null && holds(known, partnerId)) return known;
            Tenant found = walkFor(partnerId);        // the tree changed under the lookup, or the partner is new: the walk is the truth
            if (found == null) { ownerOfPartner.remove(partnerId); return null; }
            ownerOfPartner.put(partnerId, found);
            return found;
        }

        private Tenant walkFor(int partnerId) {
            for (Tenant t : root.getTenantIndex().values()) if (holds(t, partnerId)) return t;
            return null;
        }

        private static boolean holds(Tenant t, int partnerId) {
            return t.getContext() != null && t.getContext().getPartners() != null && t.getContext().getPartners().containsKey(partnerId);
        }
    }
}
