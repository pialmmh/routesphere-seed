package com.telcobright.seed.sessionflow.api;

import com.telcobright.seed.sessionflow.spi.TenantResolver;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The base's one-line lookups (R2): the helpers a lane's {@code identifyTenant} and {@code identifyPartner} are written with. A root is
 * found by its door against prime-context's identification rules (the kit's resolver); a partner inside the session's OWN tree. Every
 * answer is the thing or null — null is refused by the base ({@code NO_TENANT}, {@code PARTNER_NOT_FOUND}), never a default.
 *
 * <p>A subclass of {@link RoutesphereFlow} calls them as its own final helpers ({@code byListener(ip, port)}); a lane made with
 * {@link RoutesphereApp} gets this object as its lambdas' last argument. One per kit, shared by every session: it keeps nothing.
 */
public final class Lookups {

    private final Supplier<TenantResolver> rules;

    /** @param rules the kit's identification rules, asked again on every lookup (a root added live brings its rules, R10) */
    public Lookups(Supplier<TenantResolver> rules) { this.rules = Objects.requireNonNull(rules, "rules"); }

    // ── a root by its door (identifyTenant) ─────────────────────────────────

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
        EntryPartner entry = ctx.tree.entryOf(partnerId);
        return entry == null ? null : Tier.entry(entry);
    }

    /** The leaf of the ONE partner of the session's own tree with this name (no case); null = none, or two partners share the name. */
    public Tier byPartnerName(RoutesphereContext ctx, String partnerName) {
        if (partnerName == null || partnerName.isBlank() || ctx.tree == null) return null;
        EntryPartner entry = ctx.tree.entryNamed(partnerName);
        return entry == null ? null : Tier.entry(entry);
    }

    private String byDoor(String kind, String match) {
        return match == null || match.isBlank() ? null : byIngress(new Ingress(kind, match));
    }
}
