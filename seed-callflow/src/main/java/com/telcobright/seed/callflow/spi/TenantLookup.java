package com.telcobright.seed.callflow.spi;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.util.List;
import java.util.Optional;

/**
 * Where the chain admission finds the tenant tree: the tenant a partner lives in (prime-context's global registry
 * {@code partnerIdVsLookupDb}, else the tree walk) and a tenant by database name. A product wires it on its cached
 * tree; {@link #of(Tenant...)} answers from the roots given, walking their indexes.
 */
public interface TenantLookup {

    /** The tenant whose {@code partners} map holds the partner — the ENTRY tenant of the chain. */
    Optional<Tenant> tenantOfPartner(int partnerId);

    Optional<Tenant> tenantByDbName(String dbName);

    /** The lookup over one or more served trees (each root with its index rebuilt and its chains computed). */
    static TenantLookup of(Tenant... roots) {
        List<Tenant> all = List.of(roots);
        return new TenantLookup() {
            @Override public Optional<Tenant> tenantOfPartner(int partnerId) {
                for (Tenant root : all) {
                    for (Tenant t : root.getTenantIndex().values()) {
                        if (t.getContext() != null && t.getContext().getPartners() != null && t.getContext().getPartners().containsKey(partnerId)) return Optional.of(t);
                    }
                }
                return Optional.empty();
            }
            @Override public Optional<Tenant> tenantByDbName(String dbName) {
                if (dbName == null) return Optional.empty();
                for (Tenant root : all) {
                    Tenant t = root.findTenantByDbName(dbName);
                    if (t != null) return Optional.of(t);
                }
                return Optional.empty();
            }
        };
    }
}
