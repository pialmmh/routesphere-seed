package com.telcobright.seed.routing.policy.types.matchloadbalance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.seed.routing.group.RouteGroupSelectors;
import com.telcobright.seed.routing.group.RouteShare;
import com.telcobright.seed.routing.policy.PolicyFormatException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code match-loadbalance} DOCUMENT — the JSON in the database column — read into the model and written
 * back. Reading is strict and says where a document is wrong ({@code rules[2].match.zone: …}), because the reader
 * of that message is a person at a screen. The document never repeats what the entity's own columns hold
 * (domain, name, version, enabled): those come from the row.
 *
 * <pre>
 * {
 *   "schema": 1,
 *   "rules": [
 *     { "name": "btcl-wifi-retail",
 *       "priority": 100,
 *       "match":  { "partner": "btcl", "app": "wifi-retail", "zone": "*" },
 *       "routes": [ { "route": "bkash", "weight": 100 } ],
 *       "strategy": "weighted" }
 *   ],
 *   "default": { "reject": "no-rule-matched" }
 * }
 * </pre>
 */
public final class RuleSetJson {
    public static final int SCHEMA = 1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> RULE_KEYS = Set.of("name", "priority", "enabled", "description", "match", "routes",
        "strategy", "hash-by", "reject");
    private static final Set<String> ROUTE_KEYS = Set.of("route", "weight", "tier", "params");
    private static final Set<String> DOC_KEYS = Set.of("schema", "rules", "default", "description");

    private RuleSetJson() {}

    public static RuleSet read(String document) {
        JsonNode doc;
        try {
            doc = JSON.readTree(document == null || document.isBlank() ? "{}" : document);
        } catch (JsonProcessingException e) {
            throw new PolicyFormatException("the policy document is not JSON: " + e.getOriginalMessage());
        }
        return read(doc);
    }

    public static RuleSet read(JsonNode doc) {
        if (doc == null || !doc.isObject()) throw new PolicyFormatException("the policy document must be a JSON object");
        unknownKeys(doc, DOC_KEYS, "policy");
        int schema = doc.path("schema").asInt(SCHEMA);
        if (schema != SCHEMA) throw new PolicyFormatException("schema: " + schema + " is not understood by this build (it reads schema " + SCHEMA + ")");
        List<PolicyRule> rules = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        JsonNode rs = doc.path("rules");
        if (!rs.isMissingNode() && !rs.isArray()) throw new PolicyFormatException("rules: expected a list");
        int i = 0;
        for (JsonNode r : rs) {
            PolicyRule rule = rule(r, "rules[" + i + "]", "rule-" + (i + 1));
            if (!names.add(rule.name().toLowerCase(Locale.ROOT))) throw new PolicyFormatException("rules[" + i + "].name: '" + rule.name() + "' is used twice");
            rules.add(rule);
            i++;
        }
        PolicyRule fallback = doc.hasNonNull("default") ? rule(doc.get("default"), "default", "default") : null;
        if (fallback != null && !fallback.match().isEmpty()) throw new PolicyFormatException("default.match: the default has no match — it is what happens when no rule matched");
        if (rules.isEmpty() && fallback == null) throw new PolicyFormatException("rules: a policy needs at least one rule or a default");
        return new RuleSet(rules, fallback, doc.hasNonNull("description") ? doc.get("description").asText() : null);
    }

    private static PolicyRule rule(JsonNode r, String where, String defaultName) {
        if (r == null || !r.isObject()) throw new PolicyFormatException(where + ": expected an object");
        unknownKeys(r, RULE_KEYS, where);
        String name = r.path("name").asText(defaultName).trim();
        if (name.isEmpty()) name = defaultName;
        Map<String, AttributeMatch> match = new LinkedHashMap<>();
        JsonNode m = r.path("match");
        if (!m.isMissingNode() && !m.isObject()) throw new PolicyFormatException(where + ".match: expected an object of attribute → value");
        for (var it = m.fieldNames(); it.hasNext(); ) {
            String attr = it.next();
            String key = attr.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) throw new PolicyFormatException(where + ".match: an attribute name is empty");
            if (match.put(key, AttributeMatch.parse(m.get(attr), where + ".match." + attr)) != null) {
                throw new PolicyFormatException(where + ".match." + attr + ": the attribute is matched twice");
            }
        }
        String reject = r.hasNonNull("reject") ? r.get("reject").asText().trim() : null;
        if (reject != null && reject.isEmpty()) throw new PolicyFormatException(where + ".reject: the cause word is empty");
        List<RouteShare> routes = new ArrayList<>();
        JsonNode rt = r.path("routes");
        if (!rt.isMissingNode() && !rt.isArray()) throw new PolicyFormatException(where + ".routes: expected a list");
        int j = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode e : rt) {
            String at = where + ".routes[" + j + "]";
            RouteShare share;
            if (e.isTextual()) {
                if (e.asText().isBlank()) throw new PolicyFormatException(at + ": the route's name is missing");
                share = RouteShare.of(e.asText().trim(), 100);
            } else if (e.isObject()) {
                unknownKeys(e, ROUTE_KEYS, at);
                String route = e.path("route").asText("").trim();
                if (route.isEmpty()) throw new PolicyFormatException(at + ".route: the route's name is missing");
                if (e.has("weight") && !e.get("weight").isIntegralNumber()) throw new PolicyFormatException(at + ".weight: expected a whole number");
                if (e.has("tier") && !e.get("tier").isIntegralNumber()) throw new PolicyFormatException(at + ".tier: expected a whole number");
                int weight = e.path("weight").asInt(100);
                int tier = e.path("tier").asInt(1);
                if (weight <= 0) throw new PolicyFormatException(at + ".weight: must be above 0 (leave the route out to send it nothing)");
                if (tier < 1) throw new PolicyFormatException(at + ".tier: tiers start at 1");
                Map<String, String> params = new LinkedHashMap<>();
                JsonNode p = e.path("params");
                if (!p.isMissingNode() && !p.isObject()) throw new PolicyFormatException(at + ".params: expected an object");
                p.fieldNames().forEachRemaining(k -> params.put(k, p.get(k).asText()));
                share = new RouteShare(route, weight, tier, params);
            } else {
                throw new PolicyFormatException(at + ": expected a route name or {route, weight, tier}");
            }
            if (!seen.add(share.route().toLowerCase(Locale.ROOT))) throw new PolicyFormatException(at + ".route: '" + share.route() + "' is in the group twice");
            routes.add(share);
            j++;
        }
        if (reject == null && routes.isEmpty()) throw new PolicyFormatException(where + ": a rule needs routes (a group to send to) or reject (a cause to refuse with)");
        if (reject != null && !routes.isEmpty()) throw new PolicyFormatException(where + ": a rule either routes or rejects, not both");
        String strategy = r.path("strategy").asText("weighted").trim().toLowerCase(Locale.ROOT);
        if (!RouteGroupSelectors.known().contains(strategy)) {
            throw new PolicyFormatException(where + ".strategy: '" + strategy + "' is not a strategy (known: " + RouteGroupSelectors.known() + ")");
        }
        String hashBy = r.hasNonNull("hash-by") ? r.get("hash-by").asText().trim().toLowerCase(Locale.ROOT) : null;
        if ("hashed".equals(strategy) && (hashBy == null || hashBy.isEmpty())) {
            throw new PolicyFormatException(where + ".hash-by: strategy hashed needs the attribute whose value keeps a requester on one route");
        }
        if (r.has("priority") && !r.get("priority").isIntegralNumber()) throw new PolicyFormatException(where + ".priority: expected a whole number");
        return new PolicyRule(name, r.path("priority").asInt(0), r.path("enabled").asBoolean(true),
            r.hasNonNull("description") ? r.get("description").asText() : null, match, routes, strategy, hashBy, reject);
    }

    private static void unknownKeys(JsonNode obj, Set<String> known, String where) {
        List<String> unknown = new ArrayList<>();
        obj.fieldNames().forEachRemaining(k -> { if (!known.contains(k)) unknown.add(k); });
        if (!unknown.isEmpty()) throw new PolicyFormatException(where + ": unknown key(s) " + unknown + " (known: " + new TreeSet<>(known) + ")");
    }

    /** The document of a model — what goes back into the column (and to a screen). */
    public static ObjectNode document(RuleSet set) {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("schema", SCHEMA);
        if (set.description() != null) doc.put("description", set.description());
        ArrayNode rules = doc.putArray("rules");
        for (PolicyRule r : set.rules()) rules.add(ruleNode(r, true));
        if (set.fallback() != null) doc.set("default", ruleNode(set.fallback(), false));
        return doc;
    }

    private static ObjectNode ruleNode(PolicyRule r, boolean full) {
        ObjectNode n = JSON.createObjectNode();
        if (full) {
            n.put("name", r.name());
            n.put("priority", r.priority());
            if (!r.enabled()) n.put("enabled", false);
            if (r.description() != null) n.put("description", r.description());
            ObjectNode m = n.putObject("match");
            r.match().forEach((k, v) -> m.set(k, v.toJson()));
        }
        if (r.rejects()) {
            n.put("reject", r.reject());
        } else {
            ArrayNode routes = n.putArray("routes");
            for (RouteShare s : r.routes()) {
                ObjectNode e = routes.addObject();
                e.put("route", s.route());
                e.put("weight", s.weight());
                if (s.tier() != 1) e.put("tier", s.tier());
                if (!s.params().isEmpty()) { ObjectNode ps = e.putObject("params"); s.params().forEach(ps::put); }
            }
            if (!"weighted".equals(r.strategy())) n.put("strategy", r.strategy());
            if (r.hashBy() != null) n.put("hash-by", r.hashBy());
        }
        return n;
    }

    public static String write(RuleSet set) {
        try {
            return JSON.writeValueAsString(document(set));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the policy could not be written as JSON", e);
        }
    }
}
