package com.telcobright.seed.routing.spi;

import com.telcobright.seed.routing.api.RoutingRequest;

import java.util.List;

/**
 * The product's DIALPLAN data, as the dialplan router needs it — three questions, each answered from the product's
 * own in-memory tables (routesphere: the tenant's {@code DynamicContext}; nothing here does I/O):
 *
 * <pre>
 *   request → the prefixes of its SOURCE        (partner → callSrc → dialPlanPrefixes)
 *   prefix  → its dialplans with their percent  (dppWiseDialplanMapping[prefixId])
 *   dialplan → its routes with their priority   (Dialplan.dialplanRoutes)
 * </pre>
 */
public interface DialplanSource {

    /** The dialplan prefixes the request's source may use. Empty = the source is unknown or has none. */
    List<Prefix> prefixesOf(RoutingRequest request);

    /** The dialplans a prefix maps to; several = a percent split. */
    List<DialplanShare> dialplansOf(Prefix prefix);

    /** A dialplan's routes; the lowest priority number is tried first. */
    List<RouteEntry> routesOf(String dialplan);

    /** One row of the source's prefix table. A blank prefix = "not part of this row's match". */
    record Prefix(String id, String calledPrefix, String callingPrefix) {
        public Prefix {
            calledPrefix = calledPrefix == null ? "" : calledPrefix;
            callingPrefix = callingPrefix == null ? "" : callingPrefix;
        }
    }

    record DialplanShare(String dialplan, double percent) {}

    record RouteEntry(String route, int priority, int weight) {
        public static RouteEntry of(String route, int priority) { return new RouteEntry(route, priority, 100); }
    }
}
