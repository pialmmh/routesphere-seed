package com.telcobright.seed.tenant.internal;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One loaded tree's map, partner id → its tier — as the call switch's {@code GlobalTenantRegistry} keeps one — built ONCE per loaded
 * snapshot, never walked per session. <b>An id two tiers of one tree hold names nobody</b> (the call page's §8 "never key anything on
 * partner id alone", the ad's D5 fail-closed half): it leaves the map, both tiers are named in ONE WARN at the build, and a session whose
 * payer it is is refused {@code PARTNER_NOT_FOUND} — never "the first tier found".
 *
 * @param version   the snapshot the map was built from
 * @param byPartner the tier of every id ONE tier holds
 * @param heldByTwo the ids more than one tier holds, each with the tiers' names in schema order
 */
public record PartnerTiers(long version, Map<Integer, Tenant> byPartner, Map<Integer, List<String>> heldByTwo) {

    public static PartnerTiers of(String tenantId, long version, Tenant root, Logger log) {
        Map<Integer, Tenant> byPartner = new HashMap<>();
        Map<Integer, List<String>> heldByTwo = new TreeMap<>();
        for (Tenant tier : tiersByName(root)) {
            if (tier.getContext() == null || tier.getContext().getPartners() == null) continue;
            for (Integer partner : tier.getContext().getPartners().keySet()) if (partner != null) holdOrDisown(byPartner, heldByTwo, partner, tier);
        }
        heldByTwo.forEach((id, tiers) -> log.warn("tenant {}: partner {} is held by two tiers of the tree of '{}' ({}) — it names nobody: a session whose"
            + " payer it is is refused PARTNER_NOT_FOUND until the tree holds the id in one tier", tenantId, id, root.getDbName(), String.join(", ", tiers)));
        return new PartnerTiers(version, Map.copyOf(byPartner), Map.copyOf(heldByTwo));
    }

    private static void holdOrDisown(Map<Integer, Tenant> byPartner, Map<Integer, List<String>> heldByTwo, int partner, Tenant tier) {
        List<String> holders = heldByTwo.get(partner);
        if (holders != null) { holders.add(tier.getDbName()); return; }                      // a third tier: named too
        Tenant first = byPartner.remove(partner);
        if (first == null) { byPartner.put(partner, tier); return; }
        heldByTwo.put(partner, new ArrayList<>(List.of(first.getDbName(), tier.getDbName())));   // the id leaves the map
    }

    /** The tiers in schema-name order, so the WARN names them the same way every time. */
    private static List<Tenant> tiersByName(Tenant root) {
        List<Tenant> tiers = new ArrayList<>(root.getTenantIndex().values());
        tiers.sort(Comparator.comparing(Tenant::getDbName, Comparator.nullsLast(Comparator.naturalOrder())));
        return tiers;
    }
}
