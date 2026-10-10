package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.api.IdentificationRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The identification rules as the facade's bootstrap carries them (prime-context F4): the key {@code identification}, a list of
 * {@code {kind, match, tenant}}. A bootstrap without the key has no rules (an empty list); an entry missing a word is refused in words.
 */
public final class IdentificationRules {

    public static final String KEY = "identification";

    private IdentificationRules() {}

    public static List<IdentificationRule> fromBootstrap(Map<String, ?> bootstrap) {
        Object entries = bootstrap == null ? null : bootstrap.get(KEY);
        if (entries == null) return List.of();
        if (!(entries instanceof List<?> list)) throw new IllegalArgumentException("the bootstrap's '" + KEY + "' is not a list of {kind, match, tenant}: " + entries);
        List<IdentificationRule> rules = new ArrayList<>(list.size());
        for (Object entry : list) rules.add(ruleOf(entry));
        return List.copyOf(rules);
    }

    private static IdentificationRule ruleOf(Object entry) {
        if (!(entry instanceof Map<?, ?> map)) throw new IllegalArgumentException("an identification rule is {kind, match, tenant}, not " + entry);
        return new IdentificationRule(word(map, "kind"), word(map, "match"), word(map, "tenant"));
    }

    private static String word(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
