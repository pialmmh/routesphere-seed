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
        // the call switch's GlobalTenantRegistry.partnerIdVsLookupDb: partner id -> its owning tenant, built once (a partner id is
        // unique across a tree; a reload hands in new trees and so makes a new lookup)
        java.util.Map<Integer, Tenant> ownerOfPartner = new java.util.concurrent.ConcurrentHashMap<>();
        for (Tenant root : all) {
            for (Tenant t : root.getTenantIndex().values()) {
                if (t.getContext() == null || t.getContext().getPartners() == null) continue;
                for (Integer id : t.getContext().getPartners().keySet()) ownerOfPartner.putIfAbsent(id, t);
            }
        }
        return new TenantLookup() {
            @Override public Optional<Tenant> tenantOfPartner(int partnerId) {
                Tenant known = ownerOfPartner.get(partnerId);
                if (known != null && holds(known, partnerId)) return Optional.of(known);
                Tenant found = walkFor(partnerId);        // a tree changed under the lookup, or the partner is new: the walk is the truth
                if (found == null) { ownerOfPartner.remove(partnerId); return Optional.empty(); }
                ownerOfPartner.put(partnerId, found);
                return Optional.of(found);
            }
            private boolean holds(Tenant t, int partnerId) {
                return t.getContext() != null && t.getContext().getPartners() != null && t.getContext().getPartners().containsKey(partnerId);
            }
            private Tenant walkFor(int partnerId) {
                for (Tenant root : all) {
                    for (Tenant t : root.getTenantIndex().values()) if (holds(t, partnerId)) return t;
                }
                return null;
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
