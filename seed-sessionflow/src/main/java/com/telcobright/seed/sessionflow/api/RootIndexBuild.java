package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.mysqlentity.Route;
import com.telcobright.rtc.domainmodel.mysqlentity.RouteMetadata;
import com.telcobright.rtc.domainmodel.nonentity.DynamicContext;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The ONE walk that makes a {@link RootIndex} (P1-O): every tier of a root's tree — its partners, its routes by key and by user name, its
 * SIP accounts, its prefixes — at the snapshot's install, never per session. The hot-path guard ({@code HotPathHasNoListScanTest}) allows
 * the loops of this class because it runs once per root and load.
 */
final class RootIndexBuild {

    private static final Logger log = LoggerFactory.getLogger(RootIndex.class);

    private final String root;
    private final Map<Integer, EntryPartner> byPartnerId = new HashMap<>();
    private final Map<String, EntryPartner> byPartnerName = new HashMap<>();
    private final Map<String, EntryPartner> byRouteKey = new HashMap<>();
    private final Map<String, RouteEntry> byRouteUsername = new HashMap<>();
    private final Map<String, EntryPartner> bySipAccount = new HashMap<>();
    private final Map<String, EntryPartner> byPrefix = new HashMap<>();
    /** {@code "<map> <key>"} → its holders, in the order found: the keys that name nobody, each said once. */
    private final Map<String, List<String>> heldByTwo = new LinkedHashMap<>();
    /** The routes left out: their tier does not hold their partner, or their user name's route is not served. */
    private final List<String> leftOut = new ArrayList<>();

    RootIndexBuild(String root) { this.root = root; }

    RootIndex walk(Tenant tree) {
        for (Tenant tier : tiersByName(tree)) indexTier(tier);
        sayWhatNamesNobody();
        return frozen();
    }

    private void indexTier(Tenant tier) {
        DynamicContext context = tier.getContext();
        indexPartners(tier, orEmpty(context.getPartners()));
        indexRouteKeys(tier, context);
        indexRouteUsernames(tier, context);
        indexSipAccounts(tier, orEmpty(context.getSipAccountWisePartners()));
        indexPrefixes(tier, orEmpty(context.getPrefixWisePartners()));
    }

    // ── one tier ──────────────────────────────────────────────────────────

    private void indexPartners(Tenant tier, Map<Integer, Partner> partners) {
        for (Map.Entry<Integer, Partner> held : partners.entrySet()) {
            Partner partner = held.getValue();
            if (partner == null) continue;
            EntryPartner entry = new EntryPartner(tier, partner);
            hold(byPartnerId, "partner-id", held.getKey(), entry);
            if (partner.getPartnerName() != null && !partner.getPartnerName().isBlank()) hold(byPartnerName, "partner-name", partner.getPartnerName(), entry);
        }
    }

    /** {@code routeWisePartners} and {@code ipOrDomainWiseRoute} (both keyed by {@code route.field5}, as served): one key map. */
    private void indexRouteKeys(Tenant tier, DynamicContext context) {
        for (Map.Entry<String, Partner> served : orEmpty(context.getRouteWisePartners()).entrySet()) {
            holdRouteKey(tier, served.getKey(), served.getValue() == null ? null : served.getValue().getIdPartner());
        }
        for (Map.Entry<String, Route> served : orEmpty(context.getIpOrDomainWiseRoute()).entrySet()) {
            holdRouteKey(tier, served.getKey(), served.getValue() == null ? null : served.getValue().getIdPartner());
        }
    }

    private void holdRouteKey(Tenant tier, String key, Integer partnerId) {
        Partner partner = partnerOfTier(tier, partnerId, "the route served at '" + key + "'");
        if (partner != null) hold(byRouteKey, "route-key", key, new EntryPartner(tier, partner));
    }

    /** {@code usernameVsRouteMetaData}: the user name's route (its back reference, else the tier's route that carries the row), its partner. */
    private void indexRouteUsernames(Tenant tier, DynamicContext context) {
        Map<String, RouteMetadata> users = orEmpty(context.getUsernameVsRouteMetaData());
        if (users.isEmpty()) return;
        Map<Integer, Route> routeOfRow = routesByMetadataId(context);
        for (Map.Entry<String, RouteMetadata> user : users.entrySet()) {
            RouteMetadata row = user.getValue();
            Route route = row == null ? null : row.getRoute() != null ? row.getRoute() : routeOfRow.get(row.getId());
            if (route == null) { leftOut.add("the route of the user name '" + user.getKey() + "' in " + tier.getDbName() + " is not served"); continue; }
            Partner partner = partnerOfTier(tier, route.getIdPartner(), "the route " + route.getIdroute() + " of the user name '" + user.getKey() + "'");
            if (partner != null) holdUsername(user.getKey(), new RouteEntry(tier, row, route, partner));
        }
    }

    private void indexSipAccounts(Tenant tier, Map<String, Partner> accounts) {
        for (Map.Entry<String, Partner> account : accounts.entrySet()) {
            Partner partner = partnerOfTier(tier, account.getValue() == null ? null : account.getValue().getIdPartner(), "the SIP account '" + account.getKey() + "'");
            if (partner != null) hold(bySipAccount, "sip-account", account.getKey(), new EntryPartner(tier, partner));
        }
    }

    private void indexPrefixes(Tenant tier, Map<String, Partner> prefixes) {
        for (Map.Entry<String, Partner> prefix : prefixes.entrySet()) {
            Partner partner = partnerOfTier(tier, prefix.getValue() == null ? null : prefix.getValue().getIdPartner(), "the prefix '" + prefix.getKey() + "'");
            if (partner != null) hold(byPrefix, "prefix", prefix.getKey(), new EntryPartner(tier, partner));
        }
    }

    // ── the rules ─────────────────────────────────────────────────────────

    /** The partner as the tier's {@code partners} holds it (the same instance {@code byPartnerId} answers); null = left out, said once. */
    private Partner partnerOfTier(Tenant tier, Integer partnerId, String what) {
        Partner partner = partnerId == null ? null : orEmpty(tier.getContext().getPartners()).get(partnerId);
        if (partner == null) leftOut.add(what + " in " + tier.getDbName() + " names partner " + partnerId + ", which that tier does not hold");
        return partner;
    }

    /** A key two holders share names nobody: it leaves its map, and every holder is named in the one WARN. */
    private <K> void hold(Map<K, EntryPartner> map, String kind, K key, EntryPartner entry) {
        if (key == null) return;
        List<String> holders = heldByTwo.get(kind + " " + key);
        if (holders != null) { nameOnce(holders, holderOf(entry)); return; }
        EntryPartner first = map.putIfAbsent(key, entry);
        if (first == null || sameHolder(first, entry)) return;
        map.remove(key);
        heldByTwo.put(kind + " " + key, new ArrayList<>(List.of(holderOf(first), holderOf(entry))));
    }

    /** A user name is one key of one tier's map: a second sighting is another tier's — it names nobody. */
    private void holdUsername(String username, RouteEntry entry) {
        if (username == null) return;
        List<String> holders = heldByTwo.get("username " + username);
        if (holders != null) { nameOnce(holders, holderOf(entry.entry())); return; }
        RouteEntry first = byRouteUsername.putIfAbsent(username, entry);
        if (first == null) return;
        byRouteUsername.remove(username);
        heldByTwo.put("username " + username, new ArrayList<>(List.of(holderOf(first.entry()), holderOf(entry.entry()))));
    }

    /** A holder the two maps of one tier both name (a route key of routeWisePartners and ipOrDomainWiseRoute) is named once. */
    private static void nameOnce(List<String> holders, String holder) { if (!holders.contains(holder)) holders.add(holder); }

    private static boolean sameHolder(EntryPartner a, EntryPartner b) {
        return a.tenant() == b.tenant() && Objects.equals(a.partner().getIdPartner(), b.partner().getIdPartner());
    }

    private static String holderOf(EntryPartner entry) { return "partner " + entry.partner().getIdPartner() + " of " + entry.tenant().getDbName(); }

    private void sayWhatNamesNobody() {
        for (Map.Entry<String, List<String>> held : heldByTwo.entrySet()) {
            log.warn("root {}: {} is held by {} — it names nobody: a session that brings it is refused until the tree holds it once", root,
                LogText.clean(held.getKey()), String.join(" and ", held.getValue()));
        }
        for (String route : leftOut) log.warn("root {}: {} — left out of the root's index", root, LogText.clean(route));
    }

    // ── the walk's helpers ────────────────────────────────────────────────

    /** Every tier of the tree, the root included, in schema-name order (the WARN names them the same way every time). */
    private static List<Tenant> tiersByName(Tenant tree) {
        Set<Tenant> tiers = new LinkedHashSet<>();
        tiers.add(tree);
        tiers.addAll(tree.getTenantIndex().values());
        List<Tenant> ordered = new ArrayList<>(tiers);
        ordered.sort(Comparator.comparing(Tenant::getDbName, Comparator.nullsLast(Comparator.naturalOrder())));
        return ordered;
    }

    /** The tier's routes by the id of every {@code route_metadata} row they carry — for a row served without its back reference. */
    private static Map<Integer, Route> routesByMetadataId(DynamicContext context) {
        Map<Integer, Route> byRow = new HashMap<>();
        for (List<Route> routes : orEmpty(context.getPartnerVsRoutes()).values()) addRows(byRow, routes);
        addRows(byRow, orEmpty(context.getIdVsRoute()).values());
        addRows(byRow, orEmpty(context.getIpOrDomainWiseRoute()).values());
        return byRow;
    }

    private static void addRows(Map<Integer, Route> byRow, Collection<Route> routes) {
        if (routes == null) return;
        for (Route route : routes) {
            if (route == null || route.getRouteMetaData() == null) continue;
            for (RouteMetadata row : route.getRouteMetaData()) if (row != null && row.getId() != null) byRow.putIfAbsent(row.getId(), route);
        }
    }

    private RootIndex frozen() {
        return new RootIndex(Collections.unmodifiableMap(byPartnerId), Collections.unmodifiableMap(byPartnerName), Collections.unmodifiableMap(byRouteKey),
            Collections.unmodifiableMap(byRouteUsername), Collections.unmodifiableMap(bySipAccount), byLength(byPrefix),
            Collections.unmodifiableSet(new LinkedHashSet<>(heldByTwo.keySet())));
    }

    /** The prefixes grouped by their length: a lookup makes one get per length present, the longest first. */
    private static NavigableMap<Integer, Map<String, EntryPartner>> byLength(Map<String, EntryPartner> byPrefix) {
        TreeMap<Integer, Map<String, EntryPartner>> lengths = new TreeMap<>();
        for (Map.Entry<String, EntryPartner> prefix : byPrefix.entrySet()) {
            lengths.computeIfAbsent(prefix.getKey().length(), length -> new HashMap<>()).put(prefix.getKey(), prefix.getValue());
        }
        lengths.replaceAll((length, prefixes) -> Collections.unmodifiableMap(prefixes));
        return Collections.unmodifiableNavigableMap(lengths);
    }

    private static <K, V> Map<K, V> orEmpty(Map<K, V> map) { return map == null ? Map.of() : map; }
}
