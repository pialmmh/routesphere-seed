package com.telcobright.seed.routing.api;

import java.util.List;

/**
 * What a router answers. {@code candidates} is ORDERED: the first is the pick, the rest is the failover order
 * (the same tier's other UP routes, then the next tiers) for a product that may re-route (call, SMS); a product
 * that must not change partner mid-way (payment) uses the pick only. Everything a record needs to explain the
 * choice later is here: which router, which policy at which version, which rule — and, when asked for, the
 * TRACE of every rule that was looked at and why it did not match (the admin "simulate" answer).
 */
public record RoutingDecision(boolean routed, List<RouteChoice> candidates, String router, String policy,
                              int policyVersion, String rule, RoutingRefusal refusal, String detail,
                              List<String> trace) {

    public RoutingDecision {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        trace = trace == null ? List.of() : List.copyOf(trace);
        // THE INVARIANT a product relies on: routed = there IS a pick; refused = there is a reason and no route
        if (routed && candidates.isEmpty()) throw new IllegalArgumentException("a routed decision needs at least one candidate (the pick)");
        if (routed && refusal != null) throw new IllegalArgumentException("a routed decision carries no refusal");
        if (!routed && !candidates.isEmpty()) throw new IllegalArgumentException("a refused decision carries no candidates");
        if (!routed && refusal == null) throw new IllegalArgumentException("a refused decision needs its refusal");
    }

    /** The pick, or null when nothing was routed. */
    public RouteChoice pick() { return candidates.isEmpty() ? null : candidates.get(0); }

    public static RoutingDecision routed(List<RouteChoice> candidates, String router, String policy, int version,
                                         String rule, List<String> trace) {
        return new RoutingDecision(true, candidates, router, policy, version, rule, null, null, trace);
    }

    public static RoutingDecision refused(RoutingRefusal refusal, String detail, String router, String policy,
                                          int version, String rule, List<String> trace) {
        return new RoutingDecision(false, List.of(), router, policy, version, rule, refusal, detail, trace);
    }
}
