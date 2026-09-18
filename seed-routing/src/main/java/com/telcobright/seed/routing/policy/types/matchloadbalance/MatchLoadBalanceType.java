package com.telcobright.seed.routing.policy.types.matchloadbalance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.seed.routing.group.RouteGroupSelectors;
import com.telcobright.seed.routing.policy.CompiledPolicy;
import com.telcobright.seed.routing.policy.PolicyType;
import com.telcobright.seed.routing.policy.RoutingPolicy;

/**
 * The first policy type: MATCH the request's attributes (incoming partner, app, zone, method, amount, a number
 * prefix …), then LOAD-BALANCE over a group of outgoing routes (weights inside a tier, tiers for failover). It
 * covers the payment switch's need today — {@code partner btcl + app wifi-retail + zone → bKash 100 %} — and,
 * with the {@code prefix} form, everything a dialplan prefix table says, as rules.
 */
public final class MatchLoadBalanceType implements PolicyType {
    public static final String KEY = "match-loadbalance";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override public String key() { return KEY; }

    @Override
    public CompiledPolicy compile(RoutingPolicy policy) {
        return new RuleSetEvaluator(policy, RuleSetJson.read(policy.document()));
    }

    /** The document of a policy of this type, read — for a product that wants to look inside (tests, an export). */
    public static RuleSet ruleSetOf(RoutingPolicy policy) { return RuleSetJson.read(policy.document()); }

    @Override
    public JsonNode descriptor() {
        ObjectNode d = JSON.createObjectNode();
        d.put("type", KEY);
        d.put("title", "Match the request, load-balance over a route group");
        d.put("schema", RuleSetJson.SCHEMA);
        d.put("summary", "Rules are tried from the highest priority down (of one priority the more specific first, then by name). "
            + "The first rule whose every match holds sends the request to its route group, or refuses it. "
            + "When no rule matches, the default applies; without a default the request is refused (no-rule-matched).");

        ObjectNode doc = d.putObject("document");
        doc.put("rules", "a list of rules");
        doc.put("default", "what happens when no rule matched: { routes: [...] } or { reject: <cause> }");
        doc.put("description", "free text");

        ObjectNode rule = d.putObject("rule");
        rule.put("name", "text, unique in the policy — it goes on every routed record");
        rule.put("priority", "whole number, higher is tried first (default 0)");
        rule.put("enabled", "true | false (default true)");
        rule.put("match", "attribute → match form; every entry must hold; an attribute left out is not looked at");
        rule.put("routes", "the route group: a list of { route, weight (default 100), tier (default 1), params }");
        rule.put("strategy", "how one route of a tier is picked (default weighted)");
        rule.put("hash-by", "the attribute whose value keeps a requester on one route (strategy hashed only)");
        rule.put("reject", "refuse with this cause word instead of routing");

        ArrayNode forms = d.putArray("match-forms");
        form(forms, "equals", "\"btcl\"", "the value, compared without case");
        form(forms, "anything", "\"*\"", "any value, present or not");
        form(forms, "one-of", "[\"zone0\", \"uttara\"]", "one of the listed values");
        form(forms, "prefix", "{\"prefix\": [\"88017\", \"88013\"]}", "starts with one of — a dialplan prefix, as a rule");
        form(forms, "range", "{\"range\": [10, 500]}", "a number within, both ends included; null = open end");
        form(forms, "present", "{\"present\": false}", "the request carries (true) or does not carry (false) the attribute");
        form(forms, "not", "{\"not\": \"sandbox\"}", "the opposite of any form above");

        ArrayNode strategies = d.putArray("strategies");
        strategy(strategies, "weighted", "a weighted random pick: 90 and 10 = a 90/10 split over many requests (the default)");
        strategy(strategies, "hashed", "the same requester (hash-by) always lands on the same route while the set of UP routes holds");
        strategy(strategies, "round-robin", "turns, in proportion to the weights");
        strategy(strategies, "ordered", "the first UP route as listed — plain failover");
        for (String key : RouteGroupSelectors.known()) {
            boolean listed = false;
            for (JsonNode s : strategies) if (s.path("key").asText().equals(key)) listed = true;
            if (!listed) strategy(strategies, key, "registered by the host");
        }

        d.set("example", RuleSetJson.document(RuleSetJson.read(EXAMPLE)));
        return d;
    }

    private static void form(ArrayNode forms, String name, String json, String meaning) {
        forms.addObject().put("form", name).put("json", json).put("meaning", meaning);
    }

    private static void strategy(ArrayNode strategies, String key, String meaning) {
        strategies.addObject().put("key", key).put("meaning", meaning);
    }

    /** The owner's first policy (2026-09-18): incoming partner + app + zone → the payment routes, bKash 100 %. */
    public static final String EXAMPLE = """
        { "schema": 1,
          "rules": [
            { "name": "btcl-wifi-retail",
              "priority": 100,
              "match":  { "partner": "btcl", "app": "wifi-retail", "zone": "*" },
              "routes": [ { "route": "bkash", "weight": 100 } ],
              "strategy": "weighted" }
          ],
          "default": { "reject": "no-rule-matched" } }
        """;
}
