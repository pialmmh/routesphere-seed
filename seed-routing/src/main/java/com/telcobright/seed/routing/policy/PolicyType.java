package com.telcobright.seed.routing.policy;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A policy TYPE — the bean a policy activates. The entity's {@code type} column names one; the type reads the
 * entity's JSON document ONCE into a {@link CompiledPolicy} that routes requests with no parsing and no I/O.
 * One variant package per type under {@code policy/types/<key>/}; a host registers its own types beside the
 * built-in ones ({@link PolicyTypes#register}) — in a CDI host, by iterating {@code Instance<PolicyType>}.
 *
 * <p>The first type is {@code match-loadbalance}: match the request's attributes, load-balance over a route
 * group. Types to come need no change outside their own package: a least-cost type, a time-of-day type, a
 * percentage canary — each is a document shape + an evaluator.
 */
public interface PolicyType {

    /** The key the entity's {@code type} column holds, e.g. {@code match-loadbalance}. */
    String key();

    /**
     * Read the policy's document. Throws {@link PolicyFormatException} with a path into the JSON when the
     * document is wrong — the message is for a person at a screen.
     */
    CompiledPolicy compile(RoutingPolicy policy);

    /**
     * What a future screen needs to draw an editor for this type WITHOUT knowing it: the document's keys, the
     * match forms, the strategies, an example. Served as-is by the admin API.
     */
    JsonNode descriptor();
}
