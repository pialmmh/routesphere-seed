package com.telcobright.seed.sessionflow.spi;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.util.Optional;

/**
 * Where the chain admission finds the tenant tree. A process may serve SEVERAL trees (one per operator), and every question is asked
 * INSIDE ONE of them, named by its root: a partner id is unique inside one tree (the call switch's {@code GlobalTenantRegistry} is the
 * registry of ONE tenant instance), never across trees — every operator's root starts at partner 1, and every tree that has a reseller
 * 44 has a tier {@code res_44}. So there is no lookup "across everything served": a global answer is the bug (a call of tenant B's
 * partner 9 would climb tenant A's tree, pay A's rates on A's ledger and be written to A's CDR).
 *
 * <p>The root a question names is the call's own tenant ({@code SessionFlowContext.tenantName}). A product wires the lookup on its cached
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
     * The lookup over one or more served trees (each root with its index rebuilt and its chains computed). Each root gets its OWN map
     * partner id → its owning tenant, built once — the call switch's {@code GlobalTenantRegistry.partnerIdVsLookupDb}, per tree (a reload
     * hands in new trees and so makes a new lookup). Two roots of one name cannot be told apart: refused.
     */
    static TenantLookup of(Tenant... roots) { return new ServedTrees(roots); }
}
