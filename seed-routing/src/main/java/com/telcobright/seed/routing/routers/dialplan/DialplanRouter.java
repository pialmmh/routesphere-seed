package com.telcobright.seed.routing.routers.dialplan;

import com.telcobright.seed.routing.api.RequestRouter;
import com.telcobright.seed.routing.api.RouteChoice;
import com.telcobright.seed.routing.api.RoutingDecision;
import com.telcobright.seed.routing.api.RoutingRefusal;
import com.telcobright.seed.routing.api.RoutingRequest;
import com.telcobright.seed.routing.group.RouteGroupSelectors;
import com.telcobright.seed.routing.group.RouteShare;
import com.telcobright.seed.routing.spi.DialplanSource;
import com.telcobright.seed.routing.spi.DialplanSource.DialplanShare;
import com.telcobright.seed.routing.spi.DialplanSource.Prefix;
import com.telcobright.seed.routing.spi.DialplanSource.RouteEntry;
import com.telcobright.seed.routing.spi.RouteDirectory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DIALPLAN routing — the default of call and SMS, as routesphere does it today, written ONCE:
 *
 * <pre>
 *   the source's prefixes → the longest match, in three steps:
 *        1. called AND calling prefix both match   (longest called, then longest calling)
 *        2. called prefix only
 *        3. calling prefix only                    (request attribute match=strict: step 1 only — a masked sender)
 *   the prefix's dialplans → one, by percent (weighted random)
 *   the dialplan's routes  → a route GROUP: priority = tier (lowest number first), picked like any group
 * </pre>
 *
 * Attributes read: {@code called}, {@code calling}, {@code match} (strict | blank). What routesphere's five copies
 * of this walk never had comes free with the shared group selection: a route that is DOWN is skipped, and the
 * decision carries the failover order. With {@link RouteDirectory#ALL_UP} the pick is exactly today's.
 */
public final class DialplanRouter implements RequestRouter {
    public static final String TYPE = "dialplan";

    private final DialplanSource source;
    private final RouteDirectory directory;

    public DialplanRouter(DialplanSource source, RouteDirectory directory) {
        if (source == null) throw new IllegalArgumentException("dialplan routing needs a dialplan source");
        this.source = source;
        this.directory = directory == null ? RouteDirectory.ALL_UP : directory;
    }

    @Override public String type() { return TYPE; }

    @Override
    public RoutingDecision route(RoutingRequest request, boolean trace) {
        List<String> lines = trace ? new ArrayList<>() : null;
        String called = request.get("called");
        String calling = request.get("calling");
        boolean strict = "strict".equalsIgnoreCase(request.get("match"));
        List<Prefix> prefixes = source.prefixesOf(request);
        if (prefixes == null || prefixes.isEmpty()) {
            if (trace) lines.add("the request's source has no dialplan prefixes (unknown partner or call source)");
            return refused(RoutingRefusal.NO_RULE_MATCHED, "no dialplan prefixes for the source", null, null, lines);
        }
        Prefix prefix = longest(prefixes, called, calling, strict, lines);
        if (prefix == null) {
            return refused(RoutingRefusal.NO_RULE_MATCHED, "no matching dialplan prefix for called=" + called, null, null, lines);
        }
        DialplanShare plan = byPercent(source.dialplansOf(prefix));
        if (plan == null) {
            if (trace) lines.add("prefix " + prefix.id() + " maps to no dialplan");
            return refused(RoutingRefusal.NO_RULE_MATCHED, "prefix " + prefix.id() + " maps to no dialplan", null, "prefix:" + prefix.id(), lines);
        }
        if (trace) lines.add("prefix " + prefix.id() + " → dialplan " + plan.dialplan() + " (" + plan.percent() + " %)");
        List<RouteShare> group = group(source.routesOf(plan.dialplan()));
        if (group.isEmpty()) {
            if (trace) lines.add("dialplan " + plan.dialplan() + " has no routes");
            return refused(RoutingRefusal.NO_ROUTE_UP, "dialplan " + plan.dialplan() + " has no routes", plan.dialplan(), "prefix:" + prefix.id(), lines);
        }
        List<RouteChoice> picked = RouteGroupSelectors.of("weighted")
            .select(group, directory, request, "dialplan#" + plan.dialplan(), null, lines);
        if (picked.isEmpty()) {
            return refused(RoutingRefusal.NO_ROUTE_UP, "every route of dialplan " + plan.dialplan() + " is down or unknown", plan.dialplan(), "prefix:" + prefix.id(), lines);
        }
        return RoutingDecision.routed(picked, TYPE, plan.dialplan(), 0, "prefix:" + prefix.id(), lines);
    }

    private static RoutingDecision refused(RoutingRefusal why, String detail, String dialplan, String rule, List<String> lines) {
        return RoutingDecision.refused(why, detail, TYPE, dialplan, 0, rule, lines);
    }

    /** The three-step longest-prefix match of {@code BaseDialplanRoutingService}, unchanged. */
    static Prefix longest(List<Prefix> prefixes, String called, String calling, boolean strict, List<String> lines) {
        Prefix both = prefixes.stream()
            .filter(p -> !p.calledPrefix().isEmpty() && !p.callingPrefix().isEmpty()
                && called != null && called.startsWith(p.calledPrefix())
                && calling != null && calling.startsWith(p.callingPrefix()))
            .max(Comparator.comparingInt((Prefix p) -> p.calledPrefix().length()).thenComparingInt(p -> p.callingPrefix().length()))
            .orElse(null);
        if (both != null || strict) {
            if (lines != null) lines.add(both == null ? "strict match: no prefix matches BOTH called and calling"
                : "prefix " + both.id() + ": called " + both.calledPrefix() + " AND calling " + both.callingPrefix() + " match");
            return both;
        }
        Prefix byCalled = prefixes.stream()
            .filter(p -> !p.calledPrefix().isEmpty() && called != null && called.startsWith(p.calledPrefix()))
            .max(Comparator.comparingInt(p -> p.calledPrefix().length()))
            .orElse(null);
        if (byCalled != null) {
            if (lines != null) lines.add("prefix " + byCalled.id() + ": called " + byCalled.calledPrefix() + " matches (longest)");
            return byCalled;
        }
        Prefix byCalling = prefixes.stream()
            .filter(p -> !p.callingPrefix().isEmpty() && calling != null && calling.startsWith(p.callingPrefix()))
            .max(Comparator.comparingInt(p -> p.callingPrefix().length()))
            .orElse(null);
        if (lines != null) lines.add(byCalling == null ? "no prefix matches called=" + called + " or calling=" + calling
            : "prefix " + byCalling.id() + ": calling " + byCalling.callingPrefix() + " matches (longest)");
        return byCalling;
    }

    /** One dialplan by percent; all percents zero = the first, as today. */
    static DialplanShare byPercent(List<DialplanShare> plans) {
        if (plans == null || plans.isEmpty()) return null;
        double total = 0;
        for (DialplanShare p : plans) total += Math.max(0, p.percent());
        if (total <= 0) return plans.get(0);
        double point = ThreadLocalRandom.current().nextDouble() * total;
        double acc = 0;
        for (DialplanShare p : plans) {
            acc += Math.max(0, p.percent());
            if (point < acc) return p;
        }
        return plans.get(plans.size() - 1);
    }

    /** A dialplan's routes as a group: each distinct priority is a tier, the lowest number first. */
    static List<RouteShare> group(List<RouteEntry> routes) {
        if (routes == null || routes.isEmpty()) return List.of();
        TreeMap<Integer, Integer> tierOf = new TreeMap<>();
        for (RouteEntry r : routes) tierOf.put(r.priority(), 0);
        int t = 1;
        for (var e : tierOf.entrySet()) e.setValue(t++);
        List<RouteShare> out = new ArrayList<>();
        for (RouteEntry r : routes) out.add(RouteShare.of(r.route(), Math.max(1, r.weight()), tierOf.get(r.priority())));
        return out;
    }
}
