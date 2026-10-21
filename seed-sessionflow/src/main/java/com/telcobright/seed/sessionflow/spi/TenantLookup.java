package com.telcobright.seed.sessionflow.spi;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.RootTree;

import java.util.Optional;

/**
 * Where the chain admission finds the tenant tree. A process may serve SEVERAL trees (one per operator), and every question is asked
 * INSIDE ONE of them, named by its root: a partner id is unique inside one tree (the call switch's {@code GlobalTenantRegistry} is the
 * registry of ONE tenant instance), never across trees — every operator's root starts at partner 1, and every tree that has a reseller
 * 44 has a tier {@code res_44}. So there is no lookup "across everything served": a global answer is the bug (a call of tenant B's
 * partner 9 would climb tenant A's tree, pay A's rates on A's ledger and be written to A's CDR).
 *
 * <p>The root a question names is the session's own root ({@code RoutesphereContext.root}). A product wires the lookup on its cached
 * trees; {@link #of(Tenant...)} answers from the roots given, one map per root.
 */
public interface TenantLookup {

    /** The ROOT tenant of a served tree, by its database name — the tenant a call names. Empty = this process serves no such tree. */
    Optional<Tenant> root(String rootDbName);

    /** Inside the tree of that root: the tenant whose {@code partners} map holds the partner — the ENTRY tenant of the chain. */
    Optional<Tenant> tenantOfPartner(String rootDbName, int partnerId);

    /** Inside the tree of that root: a tenant by its database name — the root itself, or a tier of it. */
    Optional<Tenant> tenantByDbName(String rootDbName, String dbName);

    /**
     * The ROOT of a served tree by the tenant CODE it serves ({@code Tenant.name}): the one identity the facade's identification rules name,
     * a service's token carries and the Kafka suffix repeats (prime-context F4; ARCH-0077-E makes every served root's name its code). Empty =
     * no served root has that code. The root's database name may differ from the code (a store row names the schema), so a resolver's
     * answer — a code — is turned into the root here, and never sees a schema name.
     */
    Optional<Tenant> rootOfCode(String code);

    /**
     * The lookup over one or more served trees (each root with its index rebuilt and its chains computed). Each root gets its OWN map
     * partner id → its owning tenant, built once — the call switch's {@code GlobalTenantRegistry.partnerIdVsLookupDb}, per tree (a reload
     * hands in new trees and so makes a new lookup). Two roots of one name cannot be told apart: refused.
     */
    static TenantLookup of(Tenant... roots) { return new ServedTrees(roots); }

    /**
     * §D · The ROOT's current snapshot, by the root the ingress rule named (its code, or its database name): the immutable tree a session
     * takes at its start and keeps to its end — every lookup of the session is scoped to it. Empty = this process serves no such root (the
     * session fails {@code TENANT_UNAVAILABLE}). The default wraps {@link #rootOfCode} / {@link #root} at version 0; a versioned source
     * (seed-tenant's {@code TenantTrees}) answers the snapshot's own version.
     */
    default Optional<RootTree> snapshot(String root) {
        if (root == null) return Optional.empty();
        Optional<Tenant> tree = rootOfCode(root);
        if (tree.isEmpty()) tree = root(root);
        return tree.map(t -> RootTree.of(root, 0, t));
    }
}
