package com.telcobright.seed.routing;

import com.telcobright.seed.routing.api.RouteChoice;
import com.telcobright.seed.routing.api.RoutingRequest;
import com.telcobright.seed.routing.group.RouteGroupSelectors;
import com.telcobright.seed.routing.group.RouteShare;
import com.telcobright.seed.routing.spi.RouteDirectory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Load balance toward a group of outgoing routes" — the one selection both routers share. */
class RouteGroupSelectionTest {
    private static final RoutingRequest REQ = RoutingRequest.of("payment").with("msisdn", "8801711000001").build();

    private static RouteDirectory directory(Map<String, Boolean> up) {
        return new RouteDirectory() {
            @Override public boolean exists(String route) { return up.containsKey(route); }
            @Override public boolean isUp(String route) { return up.getOrDefault(route, false); }
        };
    }

    @Test
    void weighted_ninetyTen_isNinetyTenOverManyRequests() {
        List<RouteShare> group = List.of(RouteShare.of("pgw", 90), RouteShare.of("bkash-direct", 10));
        Map<String, Integer> hits = new HashMap<>();
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            List<RouteChoice> c = RouteGroupSelectors.of("weighted").select(group, RouteDirectory.ALL_UP, REQ, "k", null, null);
            hits.merge(c.get(0).route(), 1, Integer::sum);
            assertEquals(2, c.size(), "the other route of the tier is the failover");
        }
        double share = hits.get("bkash-direct") * 100.0 / n;
        assertTrue(share > 9.0 && share < 11.0, "bkash-direct got " + share + " %");
    }

    @Test
    void aSingleRouteWithAHundredPercent_isAlwaysThePick() {
        List<RouteChoice> c = RouteGroupSelectors.of(null).select(List.of(RouteShare.of("bkash", 100)), RouteDirectory.ALL_UP, REQ, "k", null, null);
        assertEquals(1, c.size());
        assertEquals("bkash", c.get(0).route());
        assertEquals(100.0, c.get(0).share());
    }

    @Test
    void aDownRouteIsNeverOffered_andItsWeightGoesToTheOthers() {
        List<RouteShare> group = List.of(RouteShare.of("a", 50), RouteShare.of("b", 30), RouteShare.of("c", 20));
        List<String> trace = new ArrayList<>();
        List<RouteChoice> c = RouteGroupSelectors.of("weighted").select(group, directory(Map.of("a", false, "b", true, "c", true)), REQ, "k", null, trace);
        assertEquals(Set.of("b", "c"), Set.of(c.get(0).route(), c.get(1).route()));
        assertEquals(2, c.size());
        double shareB = c.stream().filter(x -> x.route().equals("b")).findFirst().orElseThrow().share();
        assertEquals(60.0, shareB, "30 of the 50 that is up");
        assertTrue(trace.contains("route a: DOWN — skipped"), trace.toString());
    }

    @Test
    void tiers_theSecondTierIsUsedOnlyWhenTheFirstIsDown() {
        List<RouteShare> group = List.of(RouteShare.of("main-1", 50, 1), RouteShare.of("main-2", 50, 1), RouteShare.of("backup", 100, 2));
        List<RouteChoice> allUp = RouteGroupSelectors.of("weighted").select(group, RouteDirectory.ALL_UP, REQ, "k", null, null);
        assertEquals(1, allUp.get(0).tier());
        assertEquals("backup", allUp.get(2).route(), "the failover order ends with the next tier");
        List<RouteChoice> mainDown = RouteGroupSelectors.of("weighted").select(group,
            directory(Map.of("main-1", false, "main-2", false, "backup", true)), REQ, "k", null, null);
        assertEquals(List.of("backup"), mainDown.stream().map(RouteChoice::route).toList());
        List<RouteChoice> nothing = RouteGroupSelectors.of("weighted").select(group, directory(Map.of("main-1", false)), REQ, "k", null, null);
        assertTrue(nothing.isEmpty(), "unknown routes and down routes leave nothing to offer");
    }

    @Test
    void hashed_keepsARequesterOnOneRoute_andSpreadsRequesters() {
        List<RouteShare> group = List.of(RouteShare.of("a", 50), RouteShare.of("b", 50));
        Map<String, Integer> hits = new HashMap<>();
        for (int i = 0; i < 2_000; i++) {
            RoutingRequest r = RoutingRequest.of("payment").with("msisdn", "88017" + (10000000 + i)).build();
            String first = RouteGroupSelectors.of("hashed").select(group, RouteDirectory.ALL_UP, r, "k", "msisdn", null).get(0).route();
            for (int again = 0; again < 3; again++) {
                assertEquals(first, RouteGroupSelectors.of("hashed").select(group, RouteDirectory.ALL_UP, r, "k", "msisdn", null).get(0).route());
            }
            hits.merge(first, 1, Integer::sum);
        }
        assertTrue(hits.get("a") > 800 && hits.get("b") > 800, "spread " + hits);
    }

    @Test
    void roundRobin_givesTurnsInProportionToTheWeights() {
        List<RouteShare> group = List.of(RouteShare.of("a", 3), RouteShare.of("b", 1));
        Map<String, Integer> hits = new HashMap<>();
        for (int i = 0; i < 400; i++) hits.merge(RouteGroupSelectors.of("round-robin").select(group, RouteDirectory.ALL_UP, REQ, "rr-test", null, null).get(0).route(), 1, Integer::sum);
        assertEquals(300, hits.get("a"));
        assertEquals(100, hits.get("b"));
    }

    @Test
    void ordered_isPlainFailover() {
        List<RouteShare> group = List.of(RouteShare.of("first", 1), RouteShare.of("second", 99));
        assertEquals("first", RouteGroupSelectors.of("ordered").select(group, RouteDirectory.ALL_UP, REQ, "k", null, null).get(0).route());
        assertEquals("second", RouteGroupSelectors.of("ordered").select(group, directory(Map.of("first", false, "second", true)), REQ, "k", null, null).get(0).route());
    }

    @Test
    void anUnknownStrategyIsSaid() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RouteGroupSelectors.of("cheapest"));
        assertTrue(e.getMessage().contains("weighted"), e.getMessage());
    }

    // ── the invariant of a decision (architect's merge note 2026-09-19) ──

    @Test
    void aRoutedDecisionAlwaysHasItsPick_aRefusedOneItsReason_andEveryRouterKeepsIt() {
        assertThrows(IllegalArgumentException.class, () -> com.telcobright.seed.routing.api.RoutingDecision.routed(List.of(), "policy", "p", 1, "r", null));
        assertThrows(IllegalArgumentException.class, () -> new com.telcobright.seed.routing.api.RoutingDecision(false, List.of(), "policy", "p", 1, "r", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new com.telcobright.seed.routing.api.RoutingDecision(false,
            List.of(new RouteChoice("x", 1, 1, 100, Map.of())), "policy", "p", 1, "r", com.telcobright.seed.routing.api.RoutingRefusal.NO_ROUTE_UP, null, null));

        // both routers, over every mix of UP / DOWN / unknown routes: routed <=> a pick exists, and the pick is an UP route
        var types = com.telcobright.seed.routing.policy.PolicyTypes.defaults();
        var policy = types.compile(new com.telcobright.seed.routing.policy.RoutingPolicy("payment", "inv", "match-loadbalance", true, 1, null,
            "{ \"rules\": [ { \"name\": \"r\", \"match\": { \"partner\": \"btcl\" }, \"routes\": [ {\"route\":\"a\",\"weight\":60}, {\"route\":\"b\",\"weight\":40}, {\"route\":\"c\",\"tier\":2} ] } ], \"default\": { \"routes\": [\"d\"] } }", null, null));
        com.telcobright.seed.routing.spi.DialplanSource plan = new com.telcobright.seed.routing.spi.DialplanSource() {
            @Override public List<Prefix> prefixesOf(RoutingRequest r) { return List.of(new Prefix("p", "880", "")); }
            @Override public List<DialplanShare> dialplansOf(Prefix p) { return List.of(new DialplanShare("dp", 100)); }
            @Override public List<RouteEntry> routesOf(String d) { return List.of(RouteEntry.of("a", 1), RouteEntry.of("b", 1), RouteEntry.of("c", 2)); }
        };
        for (int mask = 0; mask < 16; mask++) {
            Map<String, Boolean> up = new HashMap<>();
            String[] names = {"a", "b", "c", "d"};
            for (int i = 0; i < 4; i++) if ((mask & (1 << i)) != 0) up.put(names[i], true); else if (i % 2 == 0) up.put(names[i], false);   // else: unknown
            RouteDirectory dir = directory(up);
            for (String partner : List.of("btcl", "someone-else")) {
                var d = policy.evaluate(RoutingRequest.of("payment").with("partner", partner).build(), dir, "policy", false);
                assertEquals(d.routed(), d.pick() != null, "mask " + mask);
                if (d.routed()) assertTrue(dir.isUp(d.pick().route()) && d.candidates().stream().allMatch(c -> dir.isUp(c.route())), "mask " + mask);
                else assertTrue(d.refusal() != null && d.candidates().isEmpty(), "mask " + mask);
            }
            var viaDialplan = new com.telcobright.seed.routing.routers.dialplan.DialplanRouter(plan, dir).route(RoutingRequest.of("sms").with("called", "8801711").build());
            assertEquals(viaDialplan.routed(), viaDialplan.pick() != null, "dialplan, mask " + mask);
            if (!viaDialplan.routed()) assertTrue(viaDialplan.refusal() != null, "dialplan, mask " + mask);
        }
    }
}
