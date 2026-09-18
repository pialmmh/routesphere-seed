package com.telcobright.seed.routing.api;

import java.util.Map;

/**
 * One outgoing route the router offers, by NAME — the product resolves the name to its own route object (an ESL
 * trunk, an SMS gateway, a payment route) with that route's protocol and parameters. {@code tier} 1 is tried
 * first; {@code share} is the route's part of its tier in percent of the routes that were UP (for the record);
 * {@code params} are per-rule overrides a policy may hand to the executor (none today).
 */
public record RouteChoice(String route, int weight, int tier, double share, Map<String, String> params) {
    public RouteChoice {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
