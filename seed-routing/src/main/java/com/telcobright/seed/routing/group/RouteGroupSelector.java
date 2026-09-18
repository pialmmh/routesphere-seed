package com.telcobright.seed.routing.group;

import com.telcobright.seed.routing.api.RouteChoice;
import com.telcobright.seed.routing.api.RoutingRequest;
import com.telcobright.seed.routing.spi.RouteDirectory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Picks from a ROUTE GROUP — the one place where "load balance toward a group of outgoing routes" is decided, for
 * the dialplan router and the policy router alike. The shape is fixed; only the pick inside a tier is the
 * strategy's:
 *
 * <pre>
 *   tiers, lowest first → the first tier with a route that is UP → the strategy picks one of its UP routes
 *   candidates = the pick, then that tier's other UP routes (heaviest first), then the later tiers' UP routes
 * </pre>
 *
 * A route that is DOWN or unknown to the product is never offered. Implementations are stateless or hold only
 * atomic counters, so one instance serves every thread.
 */
public abstract class RouteGroupSelector {

    /** The strategy's key, as a policy document names it. */
    public abstract String key();

    /** True = the failover order inside a tier is the order as listed (plain failover), not heaviest first. */
    protected boolean keepsListedOrder() { return false; }

    /** One of {@code up} (never empty): the pick for this request. {@code ruleKey} identifies the group for a stateful strategy. */
    protected abstract RouteShare pick(List<RouteShare> up, RoutingRequest request, String ruleKey, String hashBy);

    public final List<RouteChoice> select(List<RouteShare> group, RouteDirectory directory, RoutingRequest request,
                                          String ruleKey, String hashBy, List<String> trace) {
        TreeMap<Integer, List<RouteShare>> tiers = new TreeMap<>();
        for (RouteShare s : group) {
            if (!directory.exists(s.route())) {
                if (trace != null) trace.add("route " + s.route() + ": unknown to this switch — skipped");
                continue;
            }
            if (!directory.isUp(s.route())) {
                if (trace != null) trace.add("route " + s.route() + ": DOWN — skipped");
                continue;
            }
            tiers.computeIfAbsent(s.tier(), t -> new ArrayList<>()).add(s);
        }
        List<RouteChoice> out = new ArrayList<>();
        boolean first = true;
        for (var tier : tiers.entrySet()) {
            List<RouteShare> up = tier.getValue();
            double total = up.stream().mapToInt(RouteShare::weight).sum();
            List<RouteShare> ordered = new ArrayList<>(up);
            if (!keepsListedOrder()) ordered.sort(Comparator.comparingInt(RouteShare::weight).reversed().thenComparing(RouteShare::route));
            if (first) {
                RouteShare picked = pick(up, request, ruleKey, hashBy);
                ordered.remove(picked);
                ordered.add(0, picked);
                if (trace != null) trace.add("tier " + tier.getKey() + ": " + up.size() + " route(s) UP, strategy " + key() + " picked " + picked.route());
                first = false;
            }
            for (RouteShare s : ordered) {
                out.add(new RouteChoice(s.route(), s.weight(), s.tier(), total <= 0 ? 0 : Math.round(s.weight() * 1000.0 / total) / 10.0, s.params()));
            }
        }
        return out;
    }
}
