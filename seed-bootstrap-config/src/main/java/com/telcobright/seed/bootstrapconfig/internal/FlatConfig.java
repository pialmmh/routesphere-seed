package com.telcobright.seed.bootstrapconfig.internal;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The envelope's {@code config} as SmallRye reads properties: a nested map becomes {@code a.b.c}, a list {@code a.b[0]} (a map in a list
 * {@code a.b[0].c}); a key that holds a {@code .} itself is quoted ({@code quarkus.log.category."com.telcobright".level}), as SmallRye's YAML
 * source writes it. A null is no property. Numbers and booleans are their words.
 */
public final class FlatConfig {

    private FlatConfig() {}

    /** Every leaf of {@code config}, by its flat name, in name order. */
    public static Map<String, String> of(Map<String, ?> config) {
        Map<String, String> flat = new TreeMap<>();
        if (config != null) config.forEach((key, value) -> leaves(segment(key), value, flat));
        return flat;
    }

    private static void leaves(String name, Object value, Map<String, String> flat) {
        if (value == null) return;
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, inner) -> leaves(name + "." + segment(String.valueOf(key)), inner, flat));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) leaves(name + "[" + i + "]", list.get(i), flat);
        } else {
            flat.put(name, String.valueOf(value));
        }
    }

    /** One segment of a name: quoted when it holds a {@code .} (SmallRye reads {@code "a.b"} as one segment). */
    private static String segment(String key) {
        return key.indexOf('.') >= 0 && !(key.startsWith("\"") && key.endsWith("\"")) ? "\"" + key + "\"" : key;
    }
}
