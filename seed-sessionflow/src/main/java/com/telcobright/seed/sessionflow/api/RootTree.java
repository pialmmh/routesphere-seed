package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.util.Objects;
import java.util.Optional;

/**
 * ONE root's dynamic context as one session takes it (§D): the root (as the ingress rule named it), the version of the snapshot, the
 * immutable tree (the root and every nested {@code res_<id>} tier with its own {@code DynamicContext}, as prime-context served it), and
 * the tree's OWN index ({@link RootIndex}: partner id, name, route key, route user name, SIP account, prefix → the tier and the partner) —
 * never global (a partner id is unique inside one tree only: every operator's root starts at partner 1). Every lookup of the session is
 * scoped to it; a session keeps the snapshot it started with.
 *
 * <p>P1-O · made ONCE per root and load, by the step that installs the root's snapshot ({@link RootTreeInstaller}; seed-tenant's trees at
 * every swap, {@code TenantLookup.of} at its construction) — its index is built in the constructor, eagerly, never by a session.
 *
 * <p>Never persisted: the context keeps {@code root} and {@code treeVersion}; a restored session takes the root's snapshot again.
 */
public final class RootTree {

    private final String root;
    private final long version;
    private final Tenant tree;
    private final RootIndex index;

    private RootTree(String root, long version, Tenant tree) {
        this.root = Objects.requireNonNull(root, "a root tree names its root");
        this.version = version;
        this.tree = Objects.requireNonNull(tree, "a root tree holds its tree");
        this.index = RootIndex.of(root, tree);
    }

    /** The install step's ({@link RootTreeInstaller}) — the only caller: a tree is indexed once per snapshot, never per session. */
    static RootTree of(String root, long version, Tenant tree) { return new RootTree(root, version, tree); }

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

    /** The tree's own index, built at the install: every identification of a session is one get on it. */
    public RootIndex index() { return index; }

    /** The tier of THIS tree whose partners hold the id, with that partner; null = no tier holds it, or two tiers do (D5). */
    public EntryPartner entryOf(int partnerId) { return index.partner(partnerId); }

    /** The tier of THIS tree that holds the ONE partner of exactly this name, with that partner; null = none, or two partners share it. */
    public EntryPartner entryNamed(String partnerName) { return index.partnerNamed(partnerName); }

    /** The partner of a tier of THIS tree, by the tier's database name and the partner's id; null = none. */
    public Partner partnerOf(String dbName, int partnerId) {
        Tenant tier = tier(dbName).orElse(null);
        if (tier == null || tier.getContext() == null || tier.getContext().getPartners() == null) return null;
        return tier.getContext().getPartners().get(partnerId);
    }

    @Override
    public String toString() { return "root " + root + " (" + tree.getDbName() + ") v" + version; }
}
