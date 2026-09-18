package com.telcobright.seed.routing.policy.types.matchloadbalance;

import java.util.List;

/**
 * The document of a {@code match-loadbalance} policy, read: the rules as typed, and the {@code fallback} — what
 * happens when no rule matched (a route group, a refusal, or null = refuse with no-rule-matched).
 */
public record RuleSet(List<PolicyRule> rules, PolicyRule fallback, String description) {
    public RuleSet {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
