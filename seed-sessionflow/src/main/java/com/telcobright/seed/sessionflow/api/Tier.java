package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One tier of the session's OWN root tree as admission walks it, leaf to root: the tenant (a node of the root's snapshot), its index
 * (0 = the leaf), the tier below it, and — at the leaf — the partner that pays. Above the leaf the partner is found by
 * {@code identifyPartner(tier)} ({@code res_<id>} → partner {@code <id>} of this tier).
 *
 * <p>A live view of the snapshot, never persisted: the context keeps the tier's ids and amounts ({@code TierLine}).
 */
public final class Tier {

    private final Tenant tenant;
    private final int levelIndex;
    private final Tier below;
    private final Partner partner;
    private final boolean root;

    private Tier(Tenant tenant, int levelIndex, Tier below, Partner partner, boolean root) {
        this.tenant = Objects.requireNonNull(tenant, "a tier names its tenant");
        this.levelIndex = levelIndex;
        this.below = below;
        this.partner = partner;
        this.root = root;
    }

    /** The leaf: the tenant the paying partner lives in, and that partner. */
    public static Tier entry(Tenant tenant, Partner partner) {
        Objects.requireNonNull(partner, "the leaf tier names the partner that pays");
        List<Tenant> chain = tenant.getAncestorChain();
        return new Tier(tenant, 0, null, partner, chain == null || chain.size() <= 1);
    }

    /** The leaf of an {@link EntryPartner}. */
    public static Tier entry(EntryPartner entry) { return entry(entry.tenant(), entry.partner()); }

    public Tenant tenant() { return tenant; }

    public String dbName() { return tenant.getDbName(); }

    /** 0 = the leaf; the root is the last. */
    public int levelIndex() { return levelIndex; }

    /** The tier below this one; null at the leaf. */
    public Tier below() { return below; }

    /** The partner that pays at the leaf; null above it (found by {@code identifyPartner}). */
    public Partner partner() { return partner; }

    public boolean isLeaf() { return levelIndex == 0; }

    public boolean isRoot() { return root; }

    /**
     * Leaf → reseller → … → root, one tier at a time: this tier first, then every ancestor of its tenant in the ONE tree it belongs to
     * (the tenant's own ancestor chain — never a lookup across roots).
     */
    public List<Tier> ancestorChain() {
        List<Tenant> chain = tenant.getAncestorChain();
        if (chain == null || chain.isEmpty()) chain = List.of(tenant);
        List<Tier> tiers = new ArrayList<>(chain.size());
        Tier previous = this;
        tiers.add(this);
        for (int i = 1; i < chain.size(); i++) {
            Tier above = new Tier(chain.get(i), levelIndex + i, previous, null, i == chain.size() - 1);
            tiers.add(above);
            previous = above;
        }
        return List.copyOf(tiers);
    }

    @Override
    public String toString() { return "tier " + levelIndex + " (" + dbName() + (root ? ", root" : "") + ")"; }
}
