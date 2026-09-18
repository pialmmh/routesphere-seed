package com.telcobright.seed.routing.spi;

import com.telcobright.seed.routing.policy.PolicyConflictException;
import com.telcobright.seed.routing.policy.RoutingPolicy;

import java.util.List;
import java.util.Optional;

/**
 * Where routing policies live. The hot path NEVER calls a store: the catalog reads it at start, on the config
 * doorbell and on a slow poll, and routes from its own compiled snapshot. Implementations: JDBC (PostgreSQL
 * {@code jsonb} / MySQL {@code json}), in-memory (tests, a deployment whose policies come from config files
 * only). A product with its own persistence (routesphere: config-manager → {@code DynamicContext}) implements
 * this over what it already loads.
 */
public interface PolicyStore {

    Optional<RoutingPolicy> find(String domain, String name);

    /** Every policy of a domain ({@code null} = of every domain), by name. */
    List<RoutingPolicy> list(String domain);

    /**
     * Save a policy. {@code policy.version()} is the version the editor STARTED from: 0 = create (fails when the
     * name is taken), N = replace version N (fails with {@link PolicyConflictException} when the store holds
     * another version by now). The stored policy comes back with its new version and stamp; the old document
     * stays in the history.
     */
    RoutingPolicy save(RoutingPolicy policy, String by);

    /** Remove a policy (its history stays). False = there was none. */
    boolean delete(String domain, String name, String by);

    /** The versions of one policy, newest first. */
    List<RoutingPolicy> history(String domain, String name, int limit);

    /**
     * A cheap word that changes whenever anything in the store changed — the poller compares it with the last
     * one before it reads a single policy.
     */
    String fingerprint();
}
