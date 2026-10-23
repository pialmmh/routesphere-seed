package com.telcobright.seed.bootstrapconfig;

import com.telcobright.seed.bootstrapconfig.internal.FlatConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** P1-E item 12 · the envelope's config as SmallRye's names: a nested map {@code a.b.c}, a list {@code a.b[0]}, a map in a list, a dotted key quoted. */
class FlatConfigTest {

    @Test
    void mapsListsAndADottedKey() {
        Map<String, Object> config = Map.of(
            "a", Map.of("b", Map.of("c", 1, "d", true)),
            "zones", List.of("x", "y"),
            "rules", List.of(Map.of("root", "btcl", "match", "10.10.188.2:7070")),
            "log", Map.of("com.telcobright", Map.of("level", "INFO")),
            "empty", Arrays.asList((Object) null));
        assertThat(FlatConfig.of(config)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "a.b.c", "1", "a.b.d", "true",
            "zones[0]", "x", "zones[1]", "y",
            "rules[0].root", "btcl", "rules[0].match", "10.10.188.2:7070",
            "log.\"com.telcobright\".level", "INFO"));
    }
}
