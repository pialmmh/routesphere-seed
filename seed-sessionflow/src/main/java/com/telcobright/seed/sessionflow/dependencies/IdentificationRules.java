package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.api.IdentificationRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The identification rules as the facade's bootstrap carries them (prime-context F4): the key {@code identification}, a list of
 * {@code {kind, match, tenant}}. A bootstrap without the key has no rules (an empty list); an entry missing a word is refused in words.
 *
 * <p>The facade serves the key at the ROOT OF THE SERVICE'S CONFIG — the same in {@code config.yml}, {@code config.json} and the envelope's
 * {@code config} (prime-context F4, one home): hand this reader the CONFIG map (the parsed {@code config.yml}, or {@code envelope.config}),
 * never the envelope itself. The rule's {@code tenant} is the tenant CODE; {@code TenantLookup.rootOfCode} turns it into the served root.
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
