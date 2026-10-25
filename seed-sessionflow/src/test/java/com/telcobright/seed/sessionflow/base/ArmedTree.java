package com.telcobright.seed.sessionflow.base;

import com.telcobright.rtc.domainmodel.nonentity.DynamicContext;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * P1-O · A copy of a tree whose every context map refuses to be WALKED once armed: a get answers, any iteration throws — the runtime proof
 * that no session walks a tier's partners, routes or accounts after the snapshot's install (the install itself walks them, unarmed).
 */
final class ArmedTree {

    /** A map whose gets answer and whose walks, once armed, fail the test with the map's name. */
    static final class ArmedMap<K, V> extends HashMap<K, V> {
        private final String name;
        private volatile boolean armed;

        ArmedMap(String name, Map<K, V> from) {
            super(from == null ? Map.of() : from);
            this.name = name;
        }

        void arm() { armed = true; }

        private void refuseAWalk() { if (armed) throw new AssertionError("a session walked " + name + " after the snapshot's install"); }

        @Override public Set<Map.Entry<K, V>> entrySet() { refuseAWalk(); return super.entrySet(); }
        @Override public Set<K> keySet() { refuseAWalk(); return super.keySet(); }
        @Override public Collection<V> values() { refuseAWalk(); return super.values(); }
        @Override public void forEach(BiConsumer<? super K, ? super V> action) { refuseAWalk(); super.forEach(action); }
        @Override public boolean containsValue(Object value) { refuseAWalk(); return super.containsValue(value); }
        @Override public String toString() { refuseAWalk(); return super.toString(); }
        @Override public int hashCode() { refuseAWalk(); return super.hashCode(); }
        @Override public boolean equals(Object o) { refuseAWalk(); return super.equals(o); }
    }

    private final List<ArmedMap<?, ?>> maps = new ArrayList<>();
    private final Tenant root;

    private ArmedTree(Tenant built) { this.root = copyOf(built); }

    /** The tree copied tier by tier, every map the seed reads armed-able. */
    static ArmedTree of(Tenant built) { return new ArmedTree(built); }

    Tenant root() { return root; }

    /** From now on a walk of any map of any tier fails. */
    void arm() { maps.forEach(ArmedMap::arm); }

    private Tenant copyOf(Tenant built) {
        Map<String, Tenant> copies = new LinkedHashMap<>();
        for (Tenant tier : built.getTenantIndex().values()) copies.put(tier.getDbName(), tierCopy(tier));
        for (Tenant tier : built.getTenantIndex().values()) {
            if (tier.getParent() != null) copies.get(tier.getParent()).addChild(tier.getDbName(), copies.get(tier.getDbName()));
        }
        Tenant copy = copies.get(built.getDbName());
        copy.rebuildTenantIndex();
        copy.computeAncestorChains();
        return copy;
    }

    private Tenant tierCopy(Tenant tier) {
        Tenant copy = new Tenant(tier.getDbName());
        copy.setName(tier.getName());
        copy.setParent(tier.getParent());
        copy.setContext(armedContext(tier.getDbName(), tier.getContext()));
        return copy;
    }

    private DynamicContext armedContext(String tier, DynamicContext c) {
        return new DynamicContext(Map.of(), armed(tier, "partners", c.getPartners()), armed(tier, "ipOrDomainWiseRoute", c.getIpOrDomainWiseRoute()),
            armed(tier, "prefixWisePartners", c.getPrefixWisePartners()), Map.of(), Map.of(), armed(tier, "routeWisePartners", c.getRouteWisePartners()),
            Map.of(), Map.of(), armed(tier, "sipAccountWisePartners", c.getSipAccountWisePartners()), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), Map.of(), armed(tier, "partnerWiseRatePlans", c.getPartnerWiseRatePlans()), armed(tier, "ratePlans", c.getRatePlans()),
            Map.of(), List.of(), List.of(), armed(tier, "partnerVsRoutes", c.getPartnerVsRoutes()),
            armed(tier, "usernameVsRouteMetaData", c.getUsernameVsRouteMetaData()), Map.of(), Map.of(), Map.of(),
            armed(tier, "partnerIdWisePackageAccounts", c.getPartnerIdWisePackageAccounts()), armed(tier, "idVsRoute", c.getIdVsRoute()),
            Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private <K, V> Map<K, V> armed(String tier, String name, Map<K, V> from) {
        ArmedMap<K, V> map = new ArmedMap<>(tier + "." + name, from);
        maps.add(map);
        return map;
    }
}
