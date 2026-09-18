package com.telcobright.seed.routing.policy;

import com.telcobright.seed.routing.api.RoutingDecision;
import com.telcobright.seed.routing.api.RoutingRequest;
import com.telcobright.seed.routing.spi.RouteDirectory;

import java.util.List;
import java.util.Set;

/**
 * A policy ready for the hot path: immutable, thread-safe, no parsing and no I/O in {@link #evaluate}. Built once
 * per saved version by its {@link PolicyType}; the catalog swaps the whole object when a new version arrives.
 */
public interface CompiledPolicy {

    /** The entity this was compiled from (its name and version go on the decision). */
    RoutingPolicy source();

    /**
     * Route one request. {@code router} is the asking router's type, for the record. {@code trace} = also explain
     * every rule that was looked at (the admin "simulate"); off on the hot path.
     */
    RoutingDecision evaluate(RoutingRequest request, RouteDirectory directory, String router, boolean trace);

    /** Every route the document names — the validator checks them against the product's route table. */
    Set<String> routeNames();

    /**
     * What is odd but not wrong: a route the switch does not know, a rule that can never be reached, a group
     * whose weights are all on a route that is DOWN. Shown to the person who saves; never blocks a save.
     */
    List<String> warnings(RouteDirectory directory);
}
