package com.telcobright.seed.routing.api;

/**
 * THE seam: a request in, an ordered list of outgoing routes out. Which implementation answers is a matter of
 * CONFIG, never of code: {@code dialplan} (the default of call and SMS: longest prefix → a route group) or
 * {@code policy} (a named routing policy from the store). Implementations are pure on the hot path — they read
 * an immutable snapshot and do no I/O — so a router is safe on any thread and costs microseconds.
 */
public interface RequestRouter {

    /** The router type's key, as config names it: {@code dialplan}, {@code policy}. */
    String type();

    /** Route the request. Never null, never throws for a request it cannot route: the decision says why. */
    default RoutingDecision route(RoutingRequest request) { return route(request, false); }

    /** @param trace true = also explain every rule that was looked at (the admin "simulate"). */
    RoutingDecision route(RoutingRequest request, boolean trace);
}
