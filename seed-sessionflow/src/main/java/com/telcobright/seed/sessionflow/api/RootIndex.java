package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

/**
 * ONE root tree's lookups (P1-O; the owner's rule of 2026-10-25: "the DynamicContext holds the partner entities AND the route entities,
 * transformed into hashmaps by the key the business logic needs … avoiding a LIST lookup on the hot path is a STRICT rule for partner and
 * tenant identification"). Every tier of the tree is walked ONCE, when the root's snapshot is installed — the first load and every reload,
 * never per session ({@link RootTreeInstaller}) — into flat maps, key → the tier and the partner the key names. A session's every question
 * afterwards is one map get.
 *
 * <pre>
 *   byPartnerId      partner id → tier + partner              every tier's {@code partners}
 *   byPartnerName    partner name, EXACT → tier + partner      no case-blind fallback loop (the live findPartnerByName's is not copied)
 *   byRouteKey       ip · ip:port · domain, as served → …      {@code routeWisePartners} + {@code ipOrDomainWiseRoute} (route.field5)
 *   byRouteUsername  route_metadata.extra1 → tier, metadata, route, partner        {@code usernameVsRouteMetaData}
 *   bySipAccount     SIP user name → tier + partner            {@code sipAccountWisePartners}
 *   byPrefixLength   length → (prefix → tier + partner)        {@code prefixWisePartners}: the longest match, one get per length present
 * </pre>
 *
 * <p><b>A key two holders share names nobody</b> (seed-tenant's D5 rule for partner ids, kept for every key): an id, a name, an address, a
 * user name, a SIP account or a prefix that two tiers hold — or that names two partners of one tier — leaves its map, is listed in
 * {@link #heldByTwo()} and is said in ONE WARN at the build, never per session; a session that brings it is refused as if nobody held it.
 * A route's partner is the one its tier's {@code partners} holds under {@code route.idPartner} — never {@code route.getPartner()}
 * (rtc-domain's is {@code @Transient}); a route whose tier does not hold its partner is left out, said once at the build.
 *
 * <p>Immutable: built by {@link RootIndexBuild} (from {@link RootTree}'s install only); its maps never change.
 */
public final class RootIndex {

    private final Map<Integer, EntryPartner> byPartnerId;
    private final Map<String, EntryPartner> byPartnerName;
    private final Map<String, EntryPartner> byRouteKey;
    private final Map<String, RouteEntry> byRouteUsername;
    private final Map<String, EntryPartner> bySipAccount;
    private final NavigableMap<Integer, Map<String, EntryPartner>> byPrefixLength;
    private final Set<String> heldByTwo;

    RootIndex(Map<Integer, EntryPartner> byPartnerId, Map<String, EntryPartner> byPartnerName, Map<String, EntryPartner> byRouteKey,
              Map<String, RouteEntry> byRouteUsername, Map<String, EntryPartner> bySipAccount,
              NavigableMap<Integer, Map<String, EntryPartner>> byPrefixLength, Set<String> heldByTwo) {
        this.byPartnerId = byPartnerId;
        this.byPartnerName = byPartnerName;
        this.byRouteKey = byRouteKey;
        this.byRouteUsername = byRouteUsername;
        this.bySipAccount = bySipAccount;
        this.byPrefixLength = byPrefixLength;
        this.heldByTwo = heldByTwo;
    }

    /** One walk of every tier of {@code tree} — the install's, never a session's. */
    static RootIndex of(String root, Tenant tree) { return new RootIndexBuild(root).walk(tree); }

    /** The tier that holds the partner id, with that partner; null = no tier holds it, or two do. */
    public EntryPartner partner(Integer partnerId) { return partnerId == null ? null : byPartnerId.get(partnerId); }

    /** The ONE partner of the tree with exactly this name, with its tier; null = none, or two partners share the name. */
    public EntryPartner partnerNamed(String name) { return name == null ? null : byPartnerName.get(name); }

    /** The partner of {@code tier} with exactly this name; null = none there (or the name names nobody: two holders). */
    public Partner partnerNamedIn(Tenant tier, String name) {
        EntryPartner named = partnerNamed(name);
        return named != null && named.tenant() == tier ? named.partner() : null;
    }

    /** The partner whose route is served under this key — an ip, an {@code ip:port}, a domain, exactly as served; null = none. */
    public EntryPartner routeKey(String key) { return key == null ? null : byRouteKey.get(key); }

    /** The route a client authenticates on with this user name ({@code route_metadata.extra1}); null = no such user name. */
    public RouteEntry routeOfUsername(String username) { return username == null ? null : byRouteUsername.get(username); }

    /** The partner of a SIP account; null = none. */
    public EntryPartner sipAccount(String account) { return account == null ? null : bySipAccount.get(account); }

    /** The partner of the longest prefix of {@code number} the tree serves: one get per prefix length present, the longest first; null = none. */
    public EntryPartner longestPrefixOf(String number) {
        if (number == null) return null;
        for (Integer length : byPrefixLength.descendingKeySet()) {
            if (length > number.length()) continue;
            EntryPartner hit = byPrefixLength.get(length).get(number.substring(0, length));
            if (hit != null) return hit;
        }
        return null;
    }

    /** Every key two holders share ({@code "<map> <key>"}: {@code partner-id 2}, {@code route-key 10.0.0.7}, …): each names nobody. */
    public Set<String> heldByTwo() { return heldByTwo; }

    /** How many prefix lengths the tree serves: the most gets one {@link #longestPrefixOf} makes. */
    public int prefixLengths() { return byPrefixLength.size(); }
}
