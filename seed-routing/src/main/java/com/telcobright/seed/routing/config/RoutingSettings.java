package com.telcobright.seed.routing.config;

import com.telcobright.seed.routing.policy.RoutingPolicy;
import com.telcobright.seed.routing.store.PolicySeeder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The {@code routing:} block of a profile, read from the FLAT keys every config system can give (MicroProfile
 * Config, routesphere's flattened tenant YAML, Spring) — the host strips the {@code routing.} prefix and hands
 * the rest over:
 *
 * <pre>
 * routing:
 *   payment:                       # one block per domain the service routes: call | sms | payment | ad
 *     policy: btcl-wifi-retail     # a policy's NAME switches policy routing on; blank = the domain's default
 *     type: policy                 # optional — only to force a router type (dialplan | policy | one the host added)
 *   store:
 *     kind: jdbc                   # jdbc (the routing_policy table) | config (this file only, kept in memory)
 *     table: routing_policy
 *     reload-seconds: 30           # the slow poll; 0 = only the doorbell reloads
 *   seed:
 *     mode: sync                   # sync (the file is the truth) | if-absent (the store is) | off
 *   policies:                      # the policies this file declares — each one is an ENTITY with a JSON document
 *     btcl-wifi-retail:
 *       domain: payment
 *       type: match-loadbalance
 *       description: BTCL public WiFi → the payment routes
 *       document: |
 *         { "schema": 1, "rules": [ … ], "default": { "reject": "no-rule-matched" } }
 * </pre>
 */
public final class RoutingSettings {

    /** One domain's choice. {@code type} blank = decide from {@code policy} and the host's default. */
    public record Domain(String name, String type, String policy) {
        public boolean namesPolicy() { return policy != null && !policy.isBlank(); }
    }

    public record Store(String kind, String table, long reloadSeconds) {
        public boolean jdbc() { return "jdbc".equals(kind); }
    }

    private final Map<String, Domain> domains = new LinkedHashMap<>();
    private final Store store;
    private final PolicySeeder.Mode seedMode;
    private final List<RoutingPolicy> declared = new ArrayList<>();

    private RoutingSettings(Map<String, String> flat) {
        Map<String, String> keys = new LinkedHashMap<>();
        flat.forEach((k, v) -> { if (k != null && v != null) keys.put(k.trim(), v.trim()); });

        this.store = new Store(keys.getOrDefault("store.kind", "config").toLowerCase(Locale.ROOT),
            keys.getOrDefault("store.table", "routing_policy"), number(keys, "store.reload-seconds", 30));
        if (!Set.of("jdbc", "config").contains(store.kind())) throw new IllegalArgumentException("routing.store.kind: '" + store.kind() + "' — use jdbc | config");
        this.seedMode = PolicySeeder.Mode.of(keys.get("seed.mode"));

        Set<String> policyNames = new LinkedHashSet<>();
        Set<String> domainNames = new LinkedHashSet<>();
        for (String k : keys.keySet()) {
            String[] parts = k.split("\\.");
            if (parts.length < 2) continue;
            if ("policies".equals(parts[0])) {
                if (parts.length >= 3) policyNames.add(parts[1]);
            } else if (!Set.of("store", "seed").contains(parts[0]) && parts.length == 2 && Set.of("policy", "type").contains(parts[1])) {
                domainNames.add(parts[0]);
            }
        }
        for (String d : domainNames) {
            domains.put(d.toLowerCase(Locale.ROOT), new Domain(d.toLowerCase(Locale.ROOT),
                keys.getOrDefault(d + ".type", "").toLowerCase(Locale.ROOT), keys.getOrDefault(d + ".policy", "")));
        }
        for (String n : policyNames) {
            String p = "policies." + n + ".";
            String domain = keys.get(p + "domain");
            String document = keys.get(p + "document");
            if (domain == null || domain.isBlank()) throw new IllegalArgumentException("routing.policies." + n + ".domain is missing (call | sms | payment | ad)");
            if (document == null || document.isBlank()) throw new IllegalArgumentException("routing.policies." + n + ".document is missing (the policy's JSON)");
            RoutingPolicy draft = RoutingPolicy.draft(domain, n, keys.getOrDefault(p + "type", ""), keys.get(p + "description"), document);
            declared.add(draft.withEnabled(!"false".equalsIgnoreCase(keys.getOrDefault(p + "enabled", "true"))));
        }
    }

    /** @param flat the keys under {@code routing.}, the prefix already stripped */
    public static RoutingSettings from(Map<String, String> flat) { return new RoutingSettings(flat == null ? Map.of() : flat); }

    /** The domain's block; a domain the file does not mention routes by its default. */
    public Domain domain(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return domains.getOrDefault(n, new Domain(n, "", ""));
    }

    public Store store() { return store; }

    public PolicySeeder.Mode seedMode() { return seedMode; }

    /** The policies the file declares, as drafts (version 0) for the seeder. */
    public List<RoutingPolicy> declaredPolicies() { return List.copyOf(declared); }

    private static long number(Map<String, String> keys, String key, long dflt) {
        String v = keys.get(key);
        if (v == null || v.isBlank()) return dflt;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("routing." + key + ": '" + v + "' is not a number");
        }
    }
}
