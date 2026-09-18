package com.telcobright.seed.routing.policy;

import java.time.Instant;
import java.util.Locale;

/**
 * A ROUTING POLICY — THE ENTITY. One row of {@code routing_policy}: a few plain columns an index can use, and ONE
 * JSON column, {@code document}, that holds everything the policy's TYPE understands (its rules, its route groups,
 * its map of parameters). A config file names a policy ({@code routing.<domain>.policy: <name>}); the policy's
 * {@code type} names the bean that reads the document ({@link PolicyType}). So a new way of routing is a new type
 * + new documents — never a new table, never a change to the products that route.
 *
 * <p>{@code version} goes up by one on every save and rides on every routed record, so "why did this go there?"
 * has an answer months later (the history table keeps every version's document). {@code domain} keeps the four
 * switches apart in one table: {@code call}, {@code sms}, {@code payment}, {@code ad}.
 */
public record RoutingPolicy(String domain, String name, String type, boolean enabled, int version,
                            String description, String document, Instant updatedAt, String updatedBy) {

    public RoutingPolicy {
        domain = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        name = name == null ? "" : name.trim();
        type = type == null || type.isBlank() ? "" : type.trim().toLowerCase(Locale.ROOT);
        document = document == null || document.isBlank() ? "{}" : document;
    }

    /** A draft as a person (or a config file) writes it: no version yet, no stamp. */
    public static RoutingPolicy draft(String domain, String name, String type, String description, String document) {
        return new RoutingPolicy(domain, name, type, true, 0, description, document, null, null);
    }

    /** The key a store and a catalog use: {@code <domain>/<name>}, lower case. */
    public String key() { return key(domain, name); }

    public static String key(String domain, String name) {
        return (domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT)) + "/" + (name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
    }

    public RoutingPolicy withVersion(int v, Instant at, String by) {
        return new RoutingPolicy(domain, name, type, enabled, v, description, document, at, by);
    }

    public RoutingPolicy withEnabled(boolean on) {
        return new RoutingPolicy(domain, name, type, on, version, description, document, updatedAt, updatedBy);
    }

    public RoutingPolicy withDocument(String newDocument) {
        return new RoutingPolicy(domain, name, type, enabled, version, description, newDocument, updatedAt, updatedBy);
    }
}
