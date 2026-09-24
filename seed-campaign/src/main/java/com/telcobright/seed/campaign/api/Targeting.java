package com.telcobright.seed.campaign.api;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WHERE a campaign may run: for every dimension it constrains (zone, site, district, gw, …) the view's value must be
 * one of the allowed ones. A dimension with no rows is not constrained. {@code *} allows anything; a trailing {@code *}
 * is a prefix ({@code zone*}). Values compare case-insensitively.
 *
 * <p>{@link #specificity(Map)} is the ad's "prefix length": how many constrained dimensions the view satisfied
 * (a dimension allowed by {@code *} alone counts for nothing), or -1 when the campaign does not target the view.
 */
public record Targeting(Map<String, Set<String>> allow) {

    public static final Targeting ANY = new Targeting(Map.of());

    public Targeting {
        Map<String, Set<String>> m = new HashMap<>();
        allow.forEach((k, v) -> {
            Set<String> vals = new LinkedHashSet<>();
            for (String s : v) if (s != null && !s.isBlank()) vals.add(s.trim().toLowerCase(Locale.ROOT));
            if (!vals.isEmpty()) m.put(k.trim().toLowerCase(Locale.ROOT), Set.copyOf(vals));
        });
        allow = Map.copyOf(m);
    }

    public static Targeting of(String dimension, String... values) {
        return new Targeting(Map.of(dimension, Set.of(values)));
    }

    public Targeting and(String dimension, String... values) {
        Map<String, Set<String>> m = new HashMap<>(allow);
        m.put(dimension, Set.of(values));
        return new Targeting(m);
    }

    public boolean constrains(String dimension) { return allow.containsKey(dimension.toLowerCase(Locale.ROOT)); }

    /** -1 = not targeted; else the number of constrained dimensions the view matched by name (not by wildcard). */
    public int specificity(Map<String, String> facts) {
        int score = 0;
        for (var e : allow.entrySet()) {
            String value = facts.get(e.getKey());
            String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            int hit = match(e.getValue(), v);
            if (hit < 0) return -1;
            score += hit;
        }
        return score;
    }

    /** 1 = matched by a named value or prefix, 0 = matched only by "*", -1 = no match. */
    private static int match(Set<String> allowed, String v) {
        boolean wildcard = false;
        for (String a : allowed) {
            if (a.equals("*")) { wildcard = true; continue; }
            if (a.endsWith("*") ? v.startsWith(a.substring(0, a.length() - 1)) : a.equals(v)) return 1;
        }
        return wildcard ? 0 : -1;
    }
}
