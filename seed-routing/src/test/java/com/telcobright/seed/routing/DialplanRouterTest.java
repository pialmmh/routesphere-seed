package com.telcobright.seed.routing;

import com.telcobright.seed.routing.api.RoutingDecision;
import com.telcobright.seed.routing.api.RoutingRefusal;
import com.telcobright.seed.routing.api.RoutingRequest;
import com.telcobright.seed.routing.routers.dialplan.DialplanRouter;
import com.telcobright.seed.routing.spi.DialplanSource;
import com.telcobright.seed.routing.spi.RouteDirectory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dialplan walk of routesphere's {@code BaseDialplanRoutingService}, written once: the three-step longest
 * prefix, the percent split over dialplans, the routes by priority — plus what the shared group selection adds
 * (a DOWN route is skipped, the failover order is on the decision).
 */
class DialplanRouterTest {

    /** A tiny routesphere: partner → prefixes, prefix → dialplans, dialplan → routes. */
    private static final class Tables implements DialplanSource {
        final Map<String, List<Prefix>> byPartner = new HashMap<>();
        final Map<String, List<DialplanShare>> byPrefix = new HashMap<>();
        final Map<String, List<RouteEntry>> byDialplan = new HashMap<>();
        @Override public List<Prefix> prefixesOf(RoutingRequest r) { return byPartner.getOrDefault(r.get("partner"), List.of()); }
        @Override public List<DialplanShare> dialplansOf(Prefix p) { return byPrefix.getOrDefault(p.id(), List.of()); }
        @Override public List<RouteEntry> routesOf(String d) { return byDialplan.getOrDefault(d, List.of()); }
    }

    private static Tables tables() {
        Tables t = new Tables();
        t.byPartner.put("7", List.of(
            new DialplanSource.Prefix("p-880", "880", ""),
            new DialplanSource.Prefix("p-88017", "88017", ""),
            new DialplanSource.Prefix("p-mask", "88017", "BRAND"),
            new DialplanSource.Prefix("p-cli", "", "0961")));
        t.byPrefix.put("p-880", List.of(new DialplanSource.DialplanShare("dp-default", 100)));
        t.byPrefix.put("p-88017", List.of(new DialplanSource.DialplanShare("dp-gp", 100)));
        t.byPrefix.put("p-mask", List.of(new DialplanSource.DialplanShare("dp-mask", 100)));
        t.byPrefix.put("p-cli", List.of(new DialplanSource.DialplanShare("dp-cli", 100)));
        t.byDialplan.put("dp-default", List.of(DialplanSource.RouteEntry.of("igw-backup", 5), DialplanSource.RouteEntry.of("igw-main", 1)));
        t.byDialplan.put("dp-gp", List.of(DialplanSource.RouteEntry.of("gp-smsc", 1)));
        t.byDialplan.put("dp-mask", List.of(DialplanSource.RouteEntry.of("gp-masking", 1)));
        t.byDialplan.put("dp-cli", List.of(DialplanSource.RouteEntry.of("cli-route", 1)));
        return t;
    }

    private static RoutingRequest sms(String partner, String called, String calling) {
        return RoutingRequest.of("sms").with("partner", partner).with("called", called).with("calling", calling).build();
    }

    @Test
    void theLongestCalledPrefixWins() {
        DialplanRouter router = new DialplanRouter(tables(), RouteDirectory.ALL_UP);
        RoutingDecision d = router.route(sms("7", "8801712345678", "8809612345678"));
        assertTrue(d.routed());
        assertEquals("gp-smsc", d.pick().route());
        assertEquals("prefix:p-88017", d.rule());
        assertEquals("dp-gp", d.policy());
        assertEquals("dialplan", d.router());
        assertEquals("igw-main", router.route(sms("7", "8801912345678", "8809612345678")).pick().route(), "the lowest priority number is tried first");
    }

    @Test
    void calledAndCallingTogetherBeatCalledAlone_andCallingAloneIsTheLastStep() {
        DialplanRouter router = new DialplanRouter(tables(), RouteDirectory.ALL_UP);
        assertEquals("gp-masking", router.route(sms("7", "8801712345678", "BRANDX")).pick().route());
        assertEquals("cli-route", router.route(sms("7", "4412345", "09610001")).pick().route());
    }

    @Test
    void strict_aMaskedSenderNeverFallsBackToACalledOnlyPrefix() {
        DialplanRouter router = new DialplanRouter(tables(), RouteDirectory.ALL_UP);
        RoutingRequest masked = RoutingRequest.of("sms").with("partner", "7").with("called", "8801912345678").with("calling", "BRAND").with("match", "strict").build();
        RoutingDecision d = router.route(masked, true);
        assertFalse(d.routed());
        assertEquals(RoutingRefusal.NO_RULE_MATCHED, d.refusal());
        assertTrue(String.join("\n", d.trace()).contains("strict match"), d.trace().toString());
    }

    @Test
    void anUnknownSourceAndANumberNoPrefixCovers_areRefusedWithTheirReason() {
        DialplanRouter router = new DialplanRouter(tables(), RouteDirectory.ALL_UP);
        assertEquals(RoutingRefusal.NO_RULE_MATCHED, router.route(sms("99", "8801712345678", "x")).refusal());
        assertEquals(RoutingRefusal.NO_RULE_MATCHED, router.route(sms("7", "4412345", "x")).refusal());
    }

    @Test
    void aDownRouteIsSkipped_theNextPriorityTakesTheCall() {
        RouteDirectory mainDown = new RouteDirectory() {
            @Override public boolean exists(String route) { return true; }
            @Override public boolean isUp(String route) { return !route.equals("igw-main"); }
        };
        DialplanRouter router = new DialplanRouter(tables(), mainDown);
        assertEquals("igw-backup", router.route(sms("7", "8801912345678", "x")).pick().route());
        RoutingDecision allUp = new DialplanRouter(tables(), RouteDirectory.ALL_UP).route(sms("7", "8801912345678", "x"));
        assertEquals(List.of("igw-main", "igw-backup"), allUp.candidates().stream().map(c -> c.route()).toList(), "the failover order rides on the decision");
    }

    @Test
    void thePercentSplitOverDialplansHolds() {
        Tables t = tables();
        t.byPrefix.put("p-880", List.of(new DialplanSource.DialplanShare("dp-default", 75), new DialplanSource.DialplanShare("dp-cli", 25)));
        DialplanRouter router = new DialplanRouter(t, RouteDirectory.ALL_UP);
        int cli = 0;
        int n = 40_000;
        for (int i = 0; i < n; i++) if ("cli-route".equals(router.route(sms("7", "8801912345678", "x")).pick().route())) cli++;
        double share = cli * 100.0 / n;
        assertTrue(share > 23.5 && share < 26.5, "the 25 % dialplan got " + share + " %");
    }
}
