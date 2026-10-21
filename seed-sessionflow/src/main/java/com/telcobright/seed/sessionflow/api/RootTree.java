package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ONE root's dynamic context as one session takes it (§D): the root (as the ingress rule named it), the version of the snapshot, the
 * immutable tree (the root and every nested {@code res_<id>} tier with its own {@code DynamicContext}, as prime-context served it), and
 * the tree's OWN partner map — built once per snapshot, never global (a partner id is unique inside one tree only: every operator's root
 * starts at partner 1). Every lookup of the session is scoped to it; a session keeps the snapshot it started with.
 *
 * <p>Never persisted: the context keeps {@code root} and {@code treeVersion}; a restored session takes the root's snapshot again.
 */
public final class RootTree {

    private final String root;
    private final long version;
    private final Tenant tree;
    private volatile Map<Integer, Tenant> ownerOfPartner;

    private RootTree(String root, long version, Tenant tree) {
        this.root = Objects.requireNonNull(root, "a root tree names its root");
        this.version = version;
        this.tree = Objects.requireNonNull(tree, "a root tree holds its tree");
    }

    public static RootTree of(String root, long version, Tenant tree) { return new RootTree(root, version, tree); }

    /** The root as the ingress rule named it (its code). */
    public String root() { return root; }

    /** The snapshot's version (0 = a source that does not version its trees). */
    public long version() { return version; }

    /** The root tenant of the tree. */
    public Tenant tree() { return tree; }

    /** The root tenant's database name — the CDR's tenant of the root tier. */
    public String rootDbName() { return tree.getDbName(); }

    /** A tier of THIS tree by its database name. */
    public Optional<Tenant> tier(String dbName) {
        if (dbName == null) return Optional.empty();
        if (dbName.equals(tree.getDbName())) return Optional.of(tree);
        return Optional.ofNullable(tree.findTenantByDbName(dbName));
    }

    /** The tier of THIS tree whose partners hold the id, with that partner; null = this tree holds no such partner. */
    public EntryPartner entryOf(int partnerId) {
        Tenant owner = partnerMap().get(partnerId);
        if (owner == null || owner.getContext() == null || owner.getContext().getPartners() == null) return null;
        Partner partner = owner.getContext().getPartners().get(partnerId);
        return partner == null ? null : new EntryPartner(owner, partner);
    }

    /** The partner of a tier of THIS tree, by the tier's database name and the partner's id; null = none. */
    public Partner partnerOf(String dbName, int partnerId) {
        Tenant tier = tier(dbName).orElse(null);
        if (tier == null || tier.getContext() == null || tier.getContext().getPartners() == null) return null;
        return tier.getContext().getPartners().get(partnerId);
    }

    /** The tree's own partner map, built once per snapshot: an id held by TWO tiers of the tree names nobody (seed-tenant's D5 rule). */
    private Map<Integer, Tenant> partnerMap() {
        Map<Integer, Tenant> known = ownerOfPartner;
        if (known != null) return known;
        Map<Integer, Tenant> built = new ConcurrentHashMap<>();
        java.util.Set<Integer> heldTwice = new java.util.HashSet<>();
        for (Tenant t : tiersOf(tree)) {
            if (t.getContext() == null || t.getContext().getPartners() == null) continue;
            for (Integer id : t.getContext().getPartners().keySet()) {
                Tenant first = built.putIfAbsent(id, t);
                if (first != null && first != t) heldTwice.add(id);
            }
        }
        heldTwice.forEach(built::remove);
        ownerOfPartner = built;
        return built;
    }

    private static Iterable<Tenant> tiersOf(Tenant root) {
        java.util.LinkedHashSet<Tenant> tiers = new java.util.LinkedHashSet<>();
        tiers.add(root);
        Map<String, Tenant> index = root.getTenantIndex();
        if (index != null) tiers.addAll(index.values());
        return tiers;
    }

    @Override
    public String toString() { return "root " + root + " (" + tree.getDbName() + ") v" + version; }
}
