package com.telcobright.seed.routing.group;

import java.util.Map;

/**
 * One member of a ROUTE GROUP: the outgoing route's NAME, its {@code weight} (its share of the tier — 100 alone =
 * 100 %, 90 and 10 = a 90/10 split among the routes that are UP) and its {@code tier} (1 is tried first; a higher
 * tier is used only when every route of the tiers before it is DOWN — failover). {@code params} are per-member
 * overrides the product's executor may read (none today). The dialplan router builds the same groups from a
 * dialplan's routes (priority = tier), so both routers share one selection.
 */
public record RouteShare(String route, int weight, int tier, Map<String, String> params) {
    public RouteShare {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public static RouteShare of(String route, int weight) { return new RouteShare(route, weight, 1, Map.of()); }

    public static RouteShare of(String route, int weight, int tier) { return new RouteShare(route, weight, tier, Map.of()); }
}
