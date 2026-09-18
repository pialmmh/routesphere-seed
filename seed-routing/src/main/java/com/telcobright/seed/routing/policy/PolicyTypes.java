package com.telcobright.seed.routing.policy;

import com.telcobright.seed.routing.policy.types.matchloadbalance.MatchLoadBalanceType;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The registry of policy types, keyed by the word the entity's {@code type} column holds. A policy's type is
 * looked up here — never chosen by a code branch — so adding a way of routing is: write the variant package,
 * register it. An instance, not a static: a host owns its set (and a test its own).
 */
public final class PolicyTypes {
    private final Map<String, PolicyType> byKey = new ConcurrentSkipListMap<>();

    /** The built-in set: {@code match-loadbalance}. */
    public static PolicyTypes defaults() {
        return new PolicyTypes().register(new MatchLoadBalanceType());
    }

    public PolicyTypes register(PolicyType type) {
        byKey.put(type.key().toLowerCase(Locale.ROOT), type);
        return this;
    }

    /** The type of this key; a blank key means the default type. Unknown = a format error a person can read. */
    public PolicyType of(String key) {
        String k = key == null || key.isBlank() ? MatchLoadBalanceType.KEY : key.trim().toLowerCase(Locale.ROOT);
        PolicyType t = byKey.get(k);
        if (t == null) throw new PolicyFormatException("type: '" + key + "' is not a policy type this build knows (known: " + byKey.keySet() + ")");
        return t;
    }

    public CompiledPolicy compile(RoutingPolicy policy) { return of(policy.type()).compile(policy); }

    public Set<String> known() { return byKey.keySet(); }

    public Iterable<PolicyType> all() { return byKey.values(); }
}
