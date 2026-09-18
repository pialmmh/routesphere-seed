package com.telcobright.seed.routing.store;

import com.telcobright.seed.routing.policy.PolicyConflictException;
import com.telcobright.seed.routing.policy.RoutingPolicy;
import com.telcobright.seed.routing.spi.PolicyStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Policies in memory: for tests, and for a deployment whose policies come from its config files only (no
 * database yet — the files are seeded into this store at start). Same rules as the JDBC store: versions go up by
 * one, a stale save is a conflict, every version stays in the history.
 */
public final class InMemoryPolicyStore implements PolicyStore {
    private final Map<String, RoutingPolicy> byKey = new LinkedHashMap<>();
    private final Map<String, List<RoutingPolicy>> history = new LinkedHashMap<>();
    private long changes;

    @Override
    public synchronized Optional<RoutingPolicy> find(String domain, String name) {
        return Optional.ofNullable(byKey.get(RoutingPolicy.key(domain, name)));
    }

    @Override
    public synchronized List<RoutingPolicy> list(String domain) {
        List<RoutingPolicy> out = new ArrayList<>();
        for (RoutingPolicy p : byKey.values()) if (domain == null || domain.isBlank() || p.domain().equalsIgnoreCase(domain.trim())) out.add(p);
        out.sort(Comparator.comparing(RoutingPolicy::domain).thenComparing(RoutingPolicy::name));
        return out;
    }

    @Override
    public synchronized RoutingPolicy save(RoutingPolicy policy, String by) {
        PolicyNames.check(policy);
        RoutingPolicy stored = byKey.get(policy.key());
        if (policy.version() == 0 && stored != null) {
            throw new PolicyConflictException("policy '" + policy.name() + "' of " + policy.domain() + " exists already (version " + stored.version() + ")", stored.version());
        }
        if (policy.version() != 0 && (stored == null || stored.version() != policy.version())) {
            throw new PolicyConflictException("policy '" + policy.name() + "' was changed by someone else: you edited version " + policy.version()
                + ", the store holds " + (stored == null ? "none" : "version " + stored.version()), stored == null ? 0 : stored.version());
        }
        RoutingPolicy saved = PolicyNames.normalised(policy).withVersion(policy.version() + 1, Instant.now(), by);
        byKey.put(saved.key(), saved);
        history.computeIfAbsent(saved.key(), k -> new ArrayList<>()).add(0, saved);
        changes++;
        return saved;
    }

    @Override
    public synchronized boolean delete(String domain, String name, String by) {
        boolean had = byKey.remove(RoutingPolicy.key(domain, name)) != null;
        if (had) changes++;
        return had;
    }

    @Override
    public synchronized List<RoutingPolicy> history(String domain, String name, int limit) {
        List<RoutingPolicy> all = history.getOrDefault(RoutingPolicy.key(domain, name), List.of());
        return new ArrayList<>(all.subList(0, Math.min(Math.max(limit, 0), all.size())));
    }

    @Override
    public synchronized String fingerprint() { return byKey.size() + ":" + changes; }
}
