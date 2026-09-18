package com.telcobright.seed.routing.api;

/** Why nothing was routed — a closed vocabulary, so every product maps it to its own reject cause once. */
public enum RoutingRefusal {
    /** The policy the config names does not exist (or could not be loaded). */
    NO_POLICY,
    /** The policy exists and is switched off. */
    POLICY_DISABLED,
    /** No rule matched and the policy has no default. The dialplan twin: "no matching dialplan prefix". */
    NO_RULE_MATCHED,
    /** A rule matched, and every route of its group is DOWN or unknown. */
    NO_ROUTE_UP,
    /** A rule matched and its action is to refuse (a block list written as a rule). */
    REJECTED_BY_RULE;

    public String wire() { return name().toLowerCase().replace('_', '-'); }
}
