package com.telcobright.seed.routing.policy.types.matchloadbalance;

import com.telcobright.seed.routing.group.RouteShare;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One rule of a policy: WHEN every entry of {@code match} holds for the request, THEN send it to the route group
 * {@code routes} picked by {@code strategy} — or refuse it with {@code reject} (a block list written as a rule).
 * Rules are tried from the highest {@code priority} down; between two of one priority the more specific wins,
 * then the name — so the order never depends on how the document was typed.
 */
public record PolicyRule(String name, int priority, boolean enabled, String description,
                         Map<String, AttributeMatch> match, List<RouteShare> routes, String strategy,
                         String hashBy, String reject) {

    public PolicyRule {
        match = match == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(match));   // the typed order, for the screen
        routes = routes == null ? List.of() : List.copyOf(routes);
    }

    public boolean rejects() { return reject != null && !reject.isBlank(); }

    /** How specific the rule is: the sum over its attributes; a longer dialplan-style prefix counts for more. */
    public int specificity() {
        int s = 0;
        for (AttributeMatch m : match.values()) {
            s += m.specificity() * 100;
            if (m instanceof AttributeMatch.Prefix p) s += p.longest();
        }
        return s;
    }
}
