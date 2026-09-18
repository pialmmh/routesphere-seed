package com.telcobright.seed.routing.policy.types.matchloadbalance;

import com.telcobright.seed.routing.api.RouteChoice;
import com.telcobright.seed.routing.api.RoutingDecision;
import com.telcobright.seed.routing.api.RoutingRefusal;
import com.telcobright.seed.routing.api.RoutingRequest;
import com.telcobright.seed.routing.group.RouteGroupSelectors;
import com.telcobright.seed.routing.group.RouteShare;
import com.telcobright.seed.routing.policy.CompiledPolicy;
import com.telcobright.seed.routing.policy.RoutingPolicy;
import com.telcobright.seed.routing.spi.RouteDirectory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A {@code match-loadbalance} policy on the hot path. The rules are put in TRY ORDER once (priority down, then
 * the more specific, then the name) and INDEXED by the attribute most of them pin to exact values — for a payment
 * switch that is {@code app} or {@code partner} — so a request looks only at the rules written for its own app
 * plus the few general ones. Ten rules or ten thousand cost the same.
 *
 * <pre>
 *   request → index[attribute value] → rules in try order → first whose every match holds
 *           → its route group → tiers → strategy → the pick + the failover order
 *           → none matched → the default (a group or a refusal)
 * </pre>
 */
final class RuleSetEvaluator implements CompiledPolicy {
    private static final int TRACE_LIMIT = 200;
    private static final int SHADOW_LINT_LIMIT = 3_000;     // the unreachable-rule check is quadratic: skipped for a huge policy

    private final RoutingPolicy source;
    private final RuleSet set;
    private final PolicyRule[] ordered;           // the enabled rules, in try order
    private final String indexAttribute;          // null = so few rules pin anything that a plain walk is as fast
    private final Map<String, int[]> byValue;     // lower-case value → the rules (positions in `ordered`) that can match it
    private final int[] general;                  // the rules that do not pin the index attribute: tried for every value
    private final int[] all;

    RuleSetEvaluator(RoutingPolicy source, RuleSet set) {
        this.source = source;
        this.set = set;
        List<PolicyRule> enabled = new ArrayList<>();
        for (PolicyRule r : set.rules()) if (r.enabled()) enabled.add(r);
        enabled.sort(TRY_ORDER);
        this.ordered = enabled.toArray(new PolicyRule[0]);
        this.all = range(ordered.length);
        this.indexAttribute = chooseIndexAttribute(ordered);
        if (indexAttribute == null) {
            this.byValue = Map.of();
            this.general = all;
        } else {
            Map<String, List<Integer>> pinned = new LinkedHashMap<>();
            List<Integer> rest = new ArrayList<>();
            for (int i = 0; i < ordered.length; i++) {
                AttributeMatch m = ordered[i].match().get(indexAttribute);
                List<String> values = m == null ? List.of() : m.pinned();
                if (values.isEmpty()) rest.add(i);
                else for (String v : values) pinned.computeIfAbsent(v, k -> new ArrayList<>()).add(i);
            }
            this.general = toArray(rest);
            Map<String, int[]> index = new HashMap<>();
            for (var e : pinned.entrySet()) {
                List<Integer> merged = new ArrayList<>(e.getValue());
                merged.addAll(rest);
                merged.sort(Comparator.naturalOrder());     // positions ARE the try order
                index.put(e.getKey(), toArray(merged));
            }
            this.byValue = index;
        }
    }

    /** Highest priority first; of one priority the more specific; then the name — never the typing order. */
    static final Comparator<PolicyRule> TRY_ORDER = Comparator.comparingInt(PolicyRule::priority).reversed()
        .thenComparing(Comparator.comparingInt(PolicyRule::specificity).reversed())
        .thenComparing(PolicyRule::name);

    private static String chooseIndexAttribute(PolicyRule[] rules) {
        if (rules.length < 4) return null;
        Map<String, Integer> pins = new TreeMap<>();
        Map<String, Set<String>> values = new HashMap<>();
        for (PolicyRule r : rules) {
            for (var e : r.match().entrySet()) {
                List<String> p = e.getValue().pinned();
                if (p.isEmpty()) continue;
                pins.merge(e.getKey(), 1, Integer::sum);
                values.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(p);
            }
        }
        // the attribute that leaves the FEWEST rules to look at: its own bucket (pinned rules / distinct values) + the rules it does not pin
        String best = null;
        double bestCost = Double.MAX_VALUE;
        for (var e : pins.entrySet()) {
            double cost = (double) e.getValue() / values.get(e.getKey()).size() + (rules.length - e.getValue());
            if (cost < bestCost) { bestCost = cost; best = e.getKey(); }
        }
        if (bestCost >= rules.length) return null;          // nothing to gain over a plain walk
        return best;
    }

    @Override public RoutingPolicy source() { return source; }

    @Override
    public RoutingDecision evaluate(RoutingRequest request, RouteDirectory directory, String router, boolean trace) {
        List<String> lines = trace ? new ArrayList<>() : null;
        int[] candidates = all;
        if (indexAttribute != null) {
            String v = request.get(indexAttribute);
            candidates = v == null ? general : byValue.getOrDefault(v.toLowerCase(Locale.ROOT), general);
            if (trace) lines.add("index on '" + indexAttribute + "' = " + (v == null ? "(absent)" : "'" + v + "'") + ": " + candidates.length + " of " + ordered.length + " rule(s) to look at");
        }
        for (int position : candidates) {
            PolicyRule rule = ordered[position];
            String miss = firstMiss(rule, request, trace);
            if (miss == null) {
                if (trace) lines.add("rule '" + rule.name() + "' (priority " + rule.priority() + "): MATCH");
                return act(rule, rule.name(), request, directory, router, lines);
            }
            if (trace && lines.size() < TRACE_LIMIT) lines.add("rule '" + rule.name() + "' (priority " + rule.priority() + "): no — " + miss);
        }
        if (set.fallback() != null) {
            if (trace) lines.add("no rule matched: the default applies");
            return act(set.fallback(), "default", request, directory, router, lines);
        }
        if (trace) lines.add("no rule matched and the policy has no default");
        return RoutingDecision.refused(RoutingRefusal.NO_RULE_MATCHED, "no rule matched", router, source.name(), source.version(), null, lines);
    }

    /** Null when every entry of the rule's match holds; else (when asked) the first one that does not, in words. */
    private static String firstMiss(PolicyRule rule, RoutingRequest request, boolean explain) {
        for (var e : rule.match().entrySet()) {
            String value = request.get(e.getKey());
            if (!e.getValue().matches(value)) {
                return explain ? e.getKey() + ": wanted " + e.getValue().describe() + ", the request has " + (value == null ? "nothing" : "'" + value + "'") : "";
            }
        }
        return null;
    }

    private RoutingDecision act(PolicyRule rule, String ruleName, RoutingRequest request, RouteDirectory directory,
                                String router, List<String> lines) {
        if (rule.rejects()) {
            boolean byDefault = rule == set.fallback();      // the default's refusal IS "no rule matched", with the policy's own cause word
            if (lines != null) lines.add((byDefault ? "the default refuses: " : "the rule refuses: ") + rule.reject());
            return RoutingDecision.refused(byDefault ? RoutingRefusal.NO_RULE_MATCHED : RoutingRefusal.REJECTED_BY_RULE, rule.reject(), router,
                source.name(), source.version(), ruleName, lines);
        }
        List<RouteChoice> picked = RouteGroupSelectors.of(rule.strategy())
            .select(rule.routes(), directory, request, source.key() + "#" + ruleName, rule.hashBy(), lines);
        if (picked.isEmpty()) {
            return RoutingDecision.refused(RoutingRefusal.NO_ROUTE_UP, "every route of the group is down or unknown: " + names(rule.routes()),
                router, source.name(), source.version(), ruleName, lines);
        }
        return RoutingDecision.routed(picked, router, source.name(), source.version(), ruleName, lines);
    }

    @Override
    public Set<String> routeNames() {
        Set<String> out = new LinkedHashSet<>();
        for (PolicyRule r : set.rules()) for (RouteShare s : r.routes()) out.add(s.route());
        if (set.fallback() != null) for (RouteShare s : set.fallback().routes()) out.add(s.route());
        return out;
    }

    @Override
    public List<String> warnings(RouteDirectory directory) {
        List<String> out = new ArrayList<>();
        List<PolicyRule> every = new ArrayList<>(set.rules());
        if (set.fallback() != null) every.add(set.fallback());
        for (PolicyRule r : every) {
            String where = r == set.fallback() ? "default" : "rule '" + r.name() + "'";
            if (r.rejects()) continue;
            boolean anyUp = false;
            for (RouteShare s : r.routes()) {
                if (!directory.exists(s.route())) out.add(where + ": route '" + s.route() + "' is not a route of this switch — it will be skipped");
                else if (directory.isUp(s.route())) anyUp = true;
            }
            if (!anyUp) out.add(where + ": no route of the group is up now — a request that lands here is refused (no-route-up)");
        }
        for (int i = 0; i < ordered.length && ordered.length <= SHADOW_LINT_LIMIT; i++) {
            for (int j = 0; j < i; j++) {
                if (covers(ordered[j], ordered[i])) {
                    out.add("rule '" + ordered[i].name() + "' can never be reached: rule '" + ordered[j].name() + "' is tried first and matches everything it matches");
                    break;
                }
            }
        }
        for (PolicyRule r : set.rules()) if (!r.enabled()) out.add("rule '" + r.name() + "' is switched off");
        return out;
    }

    /** Does {@code first} match every request {@code second} matches? A careful under-estimate: only the plain cases. */
    private static boolean covers(PolicyRule first, PolicyRule second) {
        for (var e : first.match().entrySet()) {
            AttributeMatch a = e.getValue();
            if (a instanceof AttributeMatch.Any) continue;
            AttributeMatch b = second.match().get(e.getKey());
            if (b == null) return false;                       // the second rule accepts any value here, the first does not
            if (a.equals(b)) continue;
            List<String> pa = a.pinned();
            List<String> pb = b.pinned();
            if (!pa.isEmpty() && !pb.isEmpty() && pa.containsAll(pb)) continue;
            return false;
        }
        return true;
    }

    private static String names(List<RouteShare> group) {
        List<String> n = new ArrayList<>();
        for (RouteShare s : group) n.add(s.route());
        return n.toString();
    }

    private static int[] range(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        return a;
    }

    private static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    /** For tests: the attribute the index chose (null = a plain walk). */
    String indexAttribute() { return indexAttribute; }

    RuleSet ruleSet() { return set; }
}
