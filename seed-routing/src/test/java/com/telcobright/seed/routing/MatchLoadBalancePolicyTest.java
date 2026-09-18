package com.telcobright.seed.routing;

import com.telcobright.seed.routing.api.RoutingDecision;
import com.telcobright.seed.routing.api.RoutingRefusal;
import com.telcobright.seed.routing.api.RoutingRequest;
import com.telcobright.seed.routing.policy.CompiledPolicy;
import com.telcobright.seed.routing.policy.PolicyFormatException;
import com.telcobright.seed.routing.policy.PolicyTypes;
import com.telcobright.seed.routing.policy.RoutingPolicy;
import com.telcobright.seed.routing.policy.types.matchloadbalance.MatchLoadBalanceType;
import com.telcobright.seed.routing.policy.types.matchloadbalance.PolicyRule;
import com.telcobright.seed.routing.policy.types.matchloadbalance.RuleSet;
import com.telcobright.seed.routing.policy.types.matchloadbalance.RuleSetJson;
import com.telcobright.seed.routing.spi.RouteDirectory;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first policy type: match the request's attributes, load-balance over a route group. */
class MatchLoadBalancePolicyTest {
    private static final PolicyTypes TYPES = PolicyTypes.defaults();

    static CompiledPolicy compile(String document) {
        return TYPES.compile(new RoutingPolicy("payment", "test", "match-loadbalance", true, 7, null, document, null, null));
    }

    static RoutingRequest pay(String partner, String app, String zone, String method) {
        return RoutingRequest.of("payment").with("partner", partner).with("app", app).with("zone", zone).with("method", method).build();
    }

    @Test
    void theOwnersFirstPolicy_partnerAppZone_goesToBkashWithEverything() {
        CompiledPolicy p = compile(MatchLoadBalanceType.EXAMPLE);
        RoutingDecision d = p.evaluate(pay("btcl", "wifi-retail", "uttara", "bkash"), RouteDirectory.ALL_UP, "policy", false);
        assertTrue(d.routed());
        assertEquals("bkash", d.pick().route());
        assertEquals(100.0, d.pick().share());
        assertEquals("btcl-wifi-retail", d.rule());
        assertEquals("test", d.policy());
        assertEquals(7, d.policyVersion());
        assertTrue(d.trace().isEmpty(), "no trace on the hot path");

        RoutingDecision other = p.evaluate(pay("someone-else", "wifi-retail", "uttara", "bkash"), RouteDirectory.ALL_UP, "policy", false);
        assertFalse(other.routed());
        assertEquals(RoutingRefusal.NO_RULE_MATCHED, other.refusal());       // the default's refusal, with the policy's own cause word
        assertEquals("no-rule-matched", other.detail());
        assertEquals("default", other.rule());
    }

    @Test
    void matchingIsCaseBlind_andAnAbsentAttributeMatchesOnlyTheStar() {
        CompiledPolicy p = compile(MatchLoadBalanceType.EXAMPLE);
        assertTrue(p.evaluate(pay("BTCL", "WiFi-Retail", null, null), RouteDirectory.ALL_UP, "policy", false).routed(), "zone * matches a request without a zone");
        CompiledPolicy strict = compile("""
            { "rules": [ { "name": "r", "match": { "partner": "btcl", "zone": ["uttara", "zone0"] }, "routes": ["bkash"] } ] }""");
        assertFalse(strict.evaluate(pay("btcl", "x", null, null), RouteDirectory.ALL_UP, "policy", false).routed());
        assertTrue(strict.evaluate(pay("btcl", "x", "Zone0", null), RouteDirectory.ALL_UP, "policy", false).routed());
    }

    @Test
    void rulesAreTriedByPriorityThenByHowSpecificTheyAre_neverByTypingOrder() {
        CompiledPolicy p = compile("""
            { "rules": [
                { "name": "general",  "priority": 10, "match": { "partner": "btcl" },                         "routes": ["pgw"] },
                { "name": "the-ad",   "priority": 10, "match": { "partner": "btcl", "method": "ad" },         "routes": ["ad-virtual"] },
                { "name": "uttara",   "priority": 50, "match": { "partner": "btcl", "zone": "uttara" },       "routes": ["bkash-2"] },
                { "name": "blocked",  "priority": 90, "match": { "app": "old-app" },                          "reject": "app-retired" }
            ] }""");
        assertEquals("pgw", p.evaluate(pay("btcl", "wifi-retail", "zone0", "bkash"), RouteDirectory.ALL_UP, "policy", false).pick().route());
        assertEquals("ad-virtual", p.evaluate(pay("btcl", "wifi-retail", "zone0", "ad"), RouteDirectory.ALL_UP, "policy", false).pick().route(),
            "of one priority the more specific rule wins although it is typed second");
        assertEquals("bkash-2", p.evaluate(pay("btcl", "wifi-retail", "uttara", "ad"), RouteDirectory.ALL_UP, "policy", false).pick().route(),
            "a higher priority beats a more specific rule");
        RoutingDecision blocked = p.evaluate(pay("btcl", "old-app", "uttara", "bkash"), RouteDirectory.ALL_UP, "policy", false);
        assertEquals(RoutingRefusal.REJECTED_BY_RULE, blocked.refusal());
        assertEquals("app-retired", blocked.detail());
    }

    @Test
    void everyMatchFormWorks() {
        CompiledPolicy p = compile("""
            { "rules": [
                { "name": "gp",      "priority": 9, "match": { "called": { "prefix": ["88017", "88013"] } },                 "routes": ["gp-smsc"] },
                { "name": "big",     "priority": 8, "match": { "amount": { "range": [500, null] } },                        "routes": ["card"] },
                { "name": "small",   "priority": 7, "match": { "amount": { "range": [null, 499.99] }, "env": { "not": "sandbox" } }, "routes": ["wallet"] },
                { "name": "no-zone", "priority": 6, "match": { "zone": { "present": false } },                              "routes": ["fallback"] }
            ] }""");
        assertEquals("gp-smsc", p.evaluate(RoutingRequest.of("sms").with("called", "8801712345678").build(), RouteDirectory.ALL_UP, "policy", false).pick().route());
        assertEquals("card", p.evaluate(RoutingRequest.of("payment").with("amount", "500").build(), RouteDirectory.ALL_UP, "policy", false).pick().route());
        assertEquals("wallet", p.evaluate(RoutingRequest.of("payment").with("amount", "20.00").with("env", "live").build(), RouteDirectory.ALL_UP, "policy", false).pick().route());
        assertEquals("fallback", p.evaluate(RoutingRequest.of("payment").with("amount", "20").with("env", "sandbox").build(), RouteDirectory.ALL_UP, "policy", false).pick().route());
        assertFalse(p.evaluate(RoutingRequest.of("payment").with("amount", "abc").with("zone", "z").build(), RouteDirectory.ALL_UP, "policy", false).routed(),
            "a value that is not a number is not within a range");
    }

    @Test
    void aDisabledRuleIsSkipped_andNoMatchWithoutADefaultIsRefused() {
        CompiledPolicy p = compile("""
            { "rules": [ { "name": "off", "enabled": false, "match": { "partner": "btcl" }, "routes": ["bkash"] } ],
              "default": { "routes": [ "pgw" ] } }""");
        RoutingDecision d = p.evaluate(pay("btcl", "a", "z", "m"), RouteDirectory.ALL_UP, "policy", false);
        assertEquals("pgw", d.pick().route());
        assertEquals("default", d.rule());
        CompiledPolicy none = compile("""
            { "rules": [ { "name": "only", "match": { "partner": "x" }, "routes": ["r"] } ] }""");
        RoutingDecision refused = none.evaluate(pay("btcl", "a", "z", "m"), RouteDirectory.ALL_UP, "policy", false);
        assertEquals(RoutingRefusal.NO_RULE_MATCHED, refused.refusal());
        assertNull(refused.pick());
    }

    @Test
    void theTraceExplainsEveryRuleLookedAt() {
        CompiledPolicy p = compile("""
            { "rules": [
                { "name": "uttara", "priority": 5, "match": { "partner": "btcl", "zone": "uttara" }, "routes": ["bkash-2"] },
                { "name": "rest",   "priority": 1, "match": { "partner": "btcl" },                   "routes": ["bkash"] } ] }""");
        RoutingDecision d = p.evaluate(pay("btcl", "wifi-retail", "zone0", "bkash"), RouteDirectory.ALL_UP, "policy", true);
        String trace = String.join("\n", d.trace());
        assertTrue(trace.contains("rule 'uttara' (priority 5): no — zone: wanted 'uttara', the request has 'zone0'"), trace);
        assertTrue(trace.contains("rule 'rest' (priority 1): MATCH"), trace);
        assertTrue(trace.contains("picked bkash"), trace);
    }

    @Test
    void theIndexNeverChangesTheAnswer_tenThousandRulesAgainstAPlainWalk() {
        StringBuilder doc = new StringBuilder("{ \"rules\": [");
        int apps = 10_000;
        for (int i = 0; i < apps; i++) {
            if (i > 0) doc.append(',');
            doc.append("{ \"name\": \"app-").append(i).append("\", \"priority\": ").append(i % 7)
                .append(", \"match\": { \"partner\": \"p").append(i % 100).append("\", \"app\": \"app-").append(i).append("\" }, \"routes\": [\"route-").append(i % 13).append("\"] }");
        }
        doc.append(", { \"name\": \"any-ad\", \"priority\": 100, \"match\": { \"method\": \"ad\" }, \"routes\": [\"ad-virtual\"] }");
        doc.append(", { \"name\": \"p5-rest\", \"priority\": -1, \"match\": { \"partner\": \"p5\" }, \"routes\": [\"p5-default\"] }");
        doc.append("] }");
        CompiledPolicy p = compile(doc.toString());
        List<PolicyRule> reference = inTryOrder(RuleSetJson.read(doc.toString()));
        java.util.Random rnd = new java.util.Random(42);
        for (int n = 0; n < 5_000; n++) {
            int i = rnd.nextInt(apps + 50);
            String method = rnd.nextInt(10) == 0 ? "ad" : "bkash";
            RoutingRequest r = pay("p" + (rnd.nextInt(4) == 0 ? 5 : i % 100), "app-" + i, "z", method);
            RoutingDecision d = p.evaluate(r, RouteDirectory.ALL_UP, "policy", false);
            assertEquals(plainWalk(reference, r), d.routed() ? d.rule() : null, "request " + r.attributes());
        }
        long t0 = System.nanoTime();
        int routed = 0;
        for (int n = 0; n < 200_000; n++) {
            if (p.evaluate(pay("p" + (n % 100), "app-" + (n % apps), "z", "bkash"), RouteDirectory.ALL_UP, "policy", false).routed()) routed++;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(200_000, routed);
        assertTrue(ms < 3_000, "200k decisions over 10k rules took " + ms + " ms — the index is not used");
        System.out.println("10k rules: 200k decisions in " + ms + " ms");
    }

    /** The reference: every enabled rule in try order, no index. */
    private static String plainWalk(List<PolicyRule> inTryOrder, RoutingRequest r) {
        for (PolicyRule x : inTryOrder) {
            if (x.match().entrySet().stream().allMatch(e -> e.getValue().matches(r.get(e.getKey())))) return x.name();
        }
        return null;
    }

    private static List<PolicyRule> inTryOrder(RuleSet set) {
        return set.rules().stream().filter(PolicyRule::enabled)
            .sorted(Comparator.comparingInt(PolicyRule::priority).reversed()
                .thenComparing(Comparator.comparingInt(PolicyRule::specificity).reversed())
                .thenComparing(PolicyRule::name))
            .toList();
    }

    @Test
    void aWrongDocumentSaysWhere() {
        assertWhere("rules[0].routes[0].weight", "{ \"rules\": [ { \"name\": \"a\", \"match\": {}, \"routes\": [ { \"route\": \"x\", \"weight\": 0 } ] } ] }");
        assertWhere("rules[1].name", "{ \"rules\": [ { \"name\": \"a\", \"routes\": [\"x\"] }, { \"name\": \"A\", \"routes\": [\"y\"] } ] }");
        assertWhere("rules[0]: unknown key(s) [rout]", "{ \"rules\": [ { \"name\": \"a\", \"rout\": [\"x\"] } ] }");
        assertWhere("rules[0]: a rule needs routes", "{ \"rules\": [ { \"name\": \"a\", \"match\": { \"partner\": \"btcl\" } } ] }");
        assertWhere("rules[0]: a rule either routes or rejects", "{ \"rules\": [ { \"name\": \"a\", \"routes\": [\"x\"], \"reject\": \"no\" } ] }");
        assertWhere("rules[0].match.amount.range", "{ \"rules\": [ { \"name\": \"a\", \"match\": { \"amount\": { \"range\": [9, 1] } }, \"routes\": [\"x\"] } ] }");
        assertWhere("rules[0].match.zone: unknown operator 'regex'", "{ \"rules\": [ { \"name\": \"a\", \"match\": { \"zone\": { \"regex\": \".*\" } }, \"routes\": [\"x\"] } ] }");
        assertWhere("rules[0].strategy", "{ \"rules\": [ { \"name\": \"a\", \"routes\": [\"x\"], \"strategy\": \"cheapest\" } ] }");
        assertWhere("rules[0].hash-by", "{ \"rules\": [ { \"name\": \"a\", \"routes\": [\"x\"], \"strategy\": \"hashed\" } ] }");
        assertWhere("rules[0].routes[1].route: 'X' is in the group twice", "{ \"rules\": [ { \"name\": \"a\", \"routes\": [\"x\", \"X\"] } ] }");
        assertWhere("default.match", "{ \"default\": { \"match\": { \"a\": \"b\" }, \"routes\": [\"x\"] } }");
        assertWhere("schema: 2", "{ \"schema\": 2, \"rules\": [ { \"routes\": [\"x\"] } ] }");
        assertWhere("not JSON", "{ rules: ");
        assertWhere("at least one rule or a default", "{ }");
        PolicyFormatException unknownType = assertThrows(PolicyFormatException.class,
            () -> TYPES.compile(new RoutingPolicy("payment", "t", "least-cost", true, 1, null, "{}", null, null)));
        assertTrue(unknownType.getMessage().contains("least-cost"), unknownType.getMessage());
    }

    private static void assertWhere(String expected, String document) {
        PolicyFormatException e = assertThrows(PolicyFormatException.class, () -> compile(document), document);
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    @Test
    void theDocumentSurvivesARoundTrip() {
        String doc = """
            { "schema": 1, "description": "demo",
              "rules": [ { "name": "r1", "priority": 3, "enabled": false, "description": "d",
                           "match": { "partner": "btcl", "zone": ["a", "b"], "called": { "prefix": ["880"] },
                                      "amount": { "range": [1, null] }, "gw": { "present": true }, "env": { "not": "sandbox" }, "app": "*" },
                           "routes": [ { "route": "x", "weight": 90 }, { "route": "y", "weight": 10, "tier": 2, "params": { "k": "v" } } ],
                           "strategy": "hashed", "hash-by": "msisdn" } ],
              "default": { "reject": "nope" } }""";
        RuleSet once = RuleSetJson.read(doc);
        RuleSet twice = RuleSetJson.read(RuleSetJson.write(once));
        assertEquals(once, twice);
        assertEquals(7, once.rules().get(0).match().size());
        assertEquals("nope", twice.fallback().reject());
    }

    @Test
    void theWarningsNameWhatIsOddButNotWrong() {
        CompiledPolicy p = compile("""
            { "rules": [
                { "name": "wide",   "priority": 9, "match": { "partner": "btcl" },                    "routes": ["bkash", "ghost"] },
                { "name": "narrow", "priority": 1, "match": { "partner": "btcl", "zone": "uttara" },  "routes": ["down-route"] },
                { "name": "asleep", "enabled": false, "match": { "partner": "x" },                    "routes": ["bkash"] } ] }""");
        Map<String, Boolean> up = new HashMap<>(Map.of("bkash", true, "down-route", false));
        RouteDirectory dir = new RouteDirectory() {
            @Override public boolean exists(String route) { return up.containsKey(route); }
            @Override public boolean isUp(String route) { return up.getOrDefault(route, false); }
        };
        String w = String.join("\n", p.warnings(dir));
        assertTrue(w.contains("route 'ghost' is not a route of this switch"), w);
        assertTrue(w.contains("rule 'narrow': no route of the group is up now"), w);
        assertTrue(w.contains("rule 'narrow' can never be reached: rule 'wide' is tried first"), w);
        assertTrue(w.contains("rule 'asleep' is switched off"), w);
        assertEquals(Set.of("bkash", "ghost", "down-route"), p.routeNames());
    }

    @Test
    void theDescriptorTellsAScreenHowToDrawTheEditor() {
        var d = new MatchLoadBalanceType().descriptor();
        assertEquals("match-loadbalance", d.path("type").asText());
        assertEquals(7, d.path("match-forms").size());
        assertTrue(d.path("strategies").size() >= 4);
        assertNotNull(d.path("example").path("rules").get(0));
    }
}
