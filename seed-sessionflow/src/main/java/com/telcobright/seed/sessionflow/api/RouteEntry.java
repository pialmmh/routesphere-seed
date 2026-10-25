package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.mysqlentity.Route;
import com.telcobright.rtc.domainmodel.mysqlentity.RouteMetadata;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.util.Objects;

/**
 * A route a client authenticates on (P1-O): the tier of the root's tree that serves it, its {@code route_metadata} row (the user name in
 * {@code extra1}, the password in {@code extra2}), the route, and the partner that owns the route — the partner as its tier's
 * {@code partners} holds it under {@code route.idPartner}, the same instance {@code byPartnerId} answers.
 *
 * <p><b>Never print one whole.</b> rtc-domain's Lombok {@code toString}, {@code equals} and {@code hashCode} walk route → metadata → route
 * (a loop once the loader wires the back reference) and print {@code extra2} — the password. This record's own text names the tier, the
 * route and the partner only; it is equal to another only when it holds the same objects.
 */
public record RouteEntry(Tenant tier, RouteMetadata metadata, Route route, Partner partner) {

    public RouteEntry {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(partner, "partner");
    }

    /** The tier and the partner, as every other identification answers them. */
    public EntryPartner entry() { return new EntryPartner(tier, partner); }

    /** The route's name (a lane names its caller by it: the ad's {@code ad_caller} row, the SMS's route). */
    public String routeName() { return route.getRouteName(); }

    @Override
    public boolean equals(Object o) {
        return o instanceof RouteEntry e && e.tier == tier && e.metadata == metadata && e.route == route && e.partner == partner;
    }

    @Override
    public int hashCode() { return System.identityHashCode(metadata); }

    @Override
    public String toString() {
        return "route " + route.getIdroute() + " (" + LogText.clean(route.getRouteName()) + ") of partner " + partner.getIdPartner()
            + " in " + tier.getDbName();
    }
}
