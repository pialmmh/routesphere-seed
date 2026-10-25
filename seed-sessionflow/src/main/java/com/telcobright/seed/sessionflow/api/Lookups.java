package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.mysqlentity.Route;
import com.telcobright.seed.sessionflow.internal.Secrets;
import com.telcobright.seed.sessionflow.spi.TenantResolver;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The base's one-line lookups (R2): the helpers a lane's {@code identifyRootLevelTenant} and {@code identifyPartner} are written with. A root is
 * found by its door against prime-context's identification rules (the kit's resolver); a partner inside the session's OWN tree. Every
 * answer is the thing or null — null is refused by the base ({@code NO_TENANT}, {@code PARTNER_NOT_FOUND}), never a default.
 *
 * <p><b>The partner through its route</b> (P1-O; the owner's rule of 2026-10-25: "the client passes a param … matched with the routes key
 * indexed in the DynamicContext; route → partner"): {@link #byRouteCredentials} (the route's user name + password, the SMS lane's
 * {@code route_metadata.extra1/extra2}; {@link #bySystemId} under the SMPP name), {@link #byIp} / {@link #byDomain} ({@code route.field5}),
 * {@link #bySipAccount}, {@link #byPrefix}, {@link #byPartnerId}. Every one is a map get on the index the session's snapshot was installed
 * with ({@link RootIndex}) — never a walk of a list.
 *
 * <p>A subclass of {@link RoutesphereFlow} calls them as its own final helpers ({@code byListener(ip, port)}); a lane made with
 * {@link RoutesphereApp} gets this object as its lambdas' last argument. One per kit, shared by every session: it keeps nothing.
 */
public final class Lookups {

    private final Supplier<TenantResolver> rules;

    /** @param rules the kit's identification rules, asked again on every lookup (a root added live brings its rules, R10) */
    public Lookups(Supplier<TenantResolver> rules) { this.rules = Objects.requireNonNull(rules, "rules"); }

    // ── a root by its door (identifyRootLevelTenant) ─────────────────────────────────

    /** The root of an HTTP listener — the local address a request arrived on (the ad, the SMS's HTTP door). */
    public String byListener(String ip, int port) { return byListener(ip + ":" + port); }

    /** The same, the address written {@code <ip>:<port>}. */
    public String byListener(String ipAndPort) { return byDoor(Ingress.LISTEN, ipAndPort); }

    /** The root of the FreeSWITCH connection a CHANNEL_PARK came from (the voice call). */
    public String byEslSocket(String host, int port) { return byEslSocket(host + ":" + port); }

    /** The same, the socket written {@code <host>:<port>}. */
    public String byEslSocket(String hostAndPort) { return byDoor(Ingress.ESL, hostAndPort); }

    /** The root of a WiFi gateway and its S-VLAN. */
    public String byVlan(String gateway, int svlan) { return byDoor(Ingress.VLAN, gateway == null ? null : gateway + ":" + svlan); }

    /** The root of the Kafka topic a message was consumed from (the SMS's Kafka door). */
    public String byTopic(String name) { return byDoor(Ingress.TOPIC, name); }

    /** The root of an ingress as the lane's facts carry it; null = no door. */
    public String byIngress(Ingress ingress) { return ingress == null ? null : rules.get().tenantOf(ingress).orElse(null); }

    // ── a partner inside the session's own tree (identifyPartner) ──

    /** The leaf of a partner whose id is known (the ad's advertiser), INSIDE THE SESSION'S OWN TREE; null = no id, or no such partner there. */
    public Tier byPartnerId(RoutesphereContext ctx, Integer partnerId) {
        if (partnerId == null || ctx.tree == null) return null;
        return leafOf(ctx, ctx.tree.entryOf(partnerId));
    }

    /** The leaf of the ONE partner of the session's own tree with exactly this name (P1-O: exact); null = none, or two partners share it. */
    public Tier byPartnerName(RoutesphereContext ctx, String partnerName) {
        if (partnerName == null || partnerName.isBlank() || ctx.tree == null) return null;
        return leafOf(ctx, ctx.tree.entryNamed(partnerName));
    }

    /**
     * The leaf of the route a client authenticates on: its user name ({@code route_metadata.extra1}) and its secret, checked against
     * {@code route_metadata.extra2} ({@link Secrets}: a machine secret's {@code {sha256}} form — one SHA-256 —, the compatibility
     * {@code {pbkdf2}} form, the legacy plaintext in constant time). An unknown user name, a wrong or missing secret — null, and the session's
     * {@code identificationCause} is {@code CREDENTIALS_REJECTED}: one cause for both, and the same work (an unknown user name is checked
     * against a decoy, A4′). The secret is not kept, logged or put in any text.
     */
    public Tier byRouteCredentials(RoutesphereContext ctx, String username, String password) {
        RouteEntry route = routeOfCredentials(ctx, username, password);
        return route == null ? null : leafOf(ctx, route.entry());
    }

    /** The same check, answering the route itself (a lane that names its caller by the route: the ad's {@code ad_caller}); null as above. */
    public RouteEntry routeOfCredentials(RoutesphereContext ctx, String username, String password) {
        RootIndex index = indexOf(ctx);
        RouteEntry route = index == null ? null : index.routeOfUsername(username);
        if (route == null) Secrets.compareWithNobody(password);                 // equal work for "no such user" and "wrong secret"
        if (route != null && Secrets.verify(password, route.metadata().getExtra2())) return route;
        ctx.identificationCause = SessionCause.CREDENTIALS_REJECTED;
        return null;
    }

    /** {@link #byRouteCredentials} under the SMPP bind's name: the {@code system_id} is the route's user name. */
    public Tier bySystemId(RoutesphereContext ctx, String systemId, String password) { return byRouteCredentials(ctx, systemId, password); }

    /** The leaf of the route served at this address, exactly as served (an ip, a domain); null = none. No port is assumed. */
    public Tier byIp(RoutesphereContext ctx, String ip) { return leafOf(ctx, routeKey(ctx, ip)); }

    /** The leaf of the route served at {@code ip:port}, else at the bare {@code ip} (the port the lane saw: no {@code :5060} default). */
    public Tier byIp(RoutesphereContext ctx, String ip, int port) {
        if (ip == null) return null;
        EntryPartner withPort = routeKey(ctx, ip + ":" + port);
        return leafOf(ctx, withPort != null ? withPort : routeKey(ctx, ip));
    }

    /** The leaf of the route served at this domain ({@code route.field5}); null = none. */
    public Tier byDomain(RoutesphereContext ctx, String domain) { return leafOf(ctx, routeKey(ctx, domain)); }

    /** The leaf of a SIP account's partner ({@code sipAccountWisePartners}); null = none. */
    public Tier bySipAccount(RoutesphereContext ctx, String account) {
        RootIndex index = indexOf(ctx);
        return index == null ? null : leafOf(ctx, index.sipAccount(account));
    }

    /** The leaf of the partner of the longest prefix of {@code number} the tree serves — at most one get per prefix length; null = none. */
    public Tier byPrefix(RoutesphereContext ctx, String number) {
        RootIndex index = indexOf(ctx);
        return index == null ? null : leafOf(ctx, index.longestPrefixOf(number));
    }

    /** Every route of the leaf's partner in its tier ({@code partnerVsRoutes}: one partner, many routes); empty above the leaf, or none. */
    public List<Route> routesOf(Tier leaf) {
        if (leaf == null || leaf.partner() == null) return List.of();
        Map<Integer, List<Route>> byPartner = leaf.tenant().getContext().getPartnerVsRoutes();
        List<Route> routes = byPartner == null ? null : byPartner.get(leaf.partner().getIdPartner());
        return routes == null ? List.of() : Collections.unmodifiableList(routes);
    }

    private static RootIndex indexOf(RoutesphereContext ctx) { return ctx.tree == null ? null : ctx.tree.index(); }

    private static EntryPartner routeKey(RoutesphereContext ctx, String key) {
        RootIndex index = indexOf(ctx);
        return index == null ? null : index.routeKey(key);
    }

    /** The leaf with its snapshot's index: the partner above it is then one get (P1-O). */
    private static Tier leafOf(RoutesphereContext ctx, EntryPartner entry) {
        return entry == null ? null : Tier.entryIn(indexOf(ctx), entry.tenant(), entry.partner());
    }

    private String byDoor(String kind, String match) {
        return match == null || match.isBlank() ? null : byIngress(new Ingress(kind, match));
    }
}
