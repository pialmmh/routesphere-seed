package com.telcobright.seed.routing.group;

import com.telcobright.seed.routing.api.RoutingRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The registry of selection strategies — a policy document names one by key, never a code branch:
 * {@code weighted} (the default: a weighted random pick, 90/10 means 90/10 over many requests), {@code hashed}
 * (the same requester — the rule's {@code hash-by} attribute — always lands on the same route while the UP set
 * holds: a canary by customer), {@code round-robin} (weights as turns), {@code ordered} (the first UP route as
 * listed: plain failover).
 */
public final class RouteGroupSelectors {
    private static final Map<String, RouteGroupSelector> KNOWN = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    static {
        register(new Weighted());
        register(new Hashed());
        register(new RoundRobin());
        register(new Ordered());
    }

    private RouteGroupSelectors() {}

    public static void register(RouteGroupSelector s) { KNOWN.put(s.key(), s); }

    public static RouteGroupSelector of(String key) {
        RouteGroupSelector s = KNOWN.get(key == null ? "weighted" : key.trim().toLowerCase(Locale.ROOT));
        if (s == null) throw new IllegalArgumentException("unknown strategy '" + key + "' (known: " + KNOWN.keySet() + ")");
        return s;
    }

    public static Set<String> known() { synchronized (KNOWN) { return new java.util.LinkedHashSet<>(KNOWN.keySet()); } }

    static final class Weighted extends RouteGroupSelector {
        @Override public String key() { return "weighted"; }
        @Override protected RouteShare pick(List<RouteShare> up, RoutingRequest request, String ruleKey, String hashBy) {
            return at(up, ThreadLocalRandom.current().nextLong(total(up)));
        }
    }

    static final class Hashed extends RouteGroupSelector {
        @Override public String key() { return "hashed"; }
        @Override protected RouteShare pick(List<RouteShare> up, RoutingRequest request, String ruleKey, String hashBy) {
            String v = hashBy == null ? null : request.get(hashBy);
            if (v == null) return at(up, ThreadLocalRandom.current().nextLong(total(up)));   // nothing to hash: a fair pick
            long h = v.toLowerCase(Locale.ROOT).hashCode() * 0x9E3779B97F4A7C15L;               // spread String.hashCode
            return at(up, Math.floorMod(h >>> 16, total(up)));
        }
    }

    static final class RoundRobin extends RouteGroupSelector {
        private final ConcurrentHashMap<String, AtomicLong> turns = new ConcurrentHashMap<>();
        @Override public String key() { return "round-robin"; }
        @Override protected RouteShare pick(List<RouteShare> up, RoutingRequest request, String ruleKey, String hashBy) {
            long n = turns.computeIfAbsent(ruleKey == null ? "" : ruleKey, k -> new AtomicLong()).getAndIncrement();
            return at(up, Math.floorMod(n, total(up)));
        }
    }

    static final class Ordered extends RouteGroupSelector {
        @Override public String key() { return "ordered"; }
        @Override protected boolean keepsListedOrder() { return true; }
        @Override protected RouteShare pick(List<RouteShare> up, RoutingRequest request, String ruleKey, String hashBy) {
            return up.get(0);
        }
    }

    private static long total(List<RouteShare> up) {
        long t = 0;
        for (RouteShare s : up) t += s.weight();
        return Math.max(1, t);
    }

    /** The route that owns position {@code point} on the line of cumulative weights. */
    private static RouteShare at(List<RouteShare> up, long point) {
        long acc = 0;
        for (RouteShare s : up) {
            acc += s.weight();
            if (point < acc) return s;
        }
        return up.get(up.size() - 1);
    }
}
