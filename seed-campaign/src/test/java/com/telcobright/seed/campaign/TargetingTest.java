package com.telcobright.seed.campaign;

import com.telcobright.seed.campaign.api.Targeting;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Zone / site / district targeting: an absent dimension is free, a named match counts, a wildcard matches but counts nothing. */
class TargetingTest {

    private static final Map<String, String> MOGHBAZAR = Map.of("zone", "zone0", "site", "moghbazar", "district", "dhaka", "gw", "wifi-gw2");

    @Test
    void any_targets_everything_with_zero_specificity() {
        assertThat(Targeting.ANY.specificity(MOGHBAZAR)).isZero();
        assertThat(Targeting.ANY.specificity(Map.of())).isZero();
    }

    @Test
    void specificity_counts_the_named_dimensions_that_matched() {
        assertThat(Targeting.of("zone", "zone0").specificity(MOGHBAZAR)).isEqualTo(1);
        assertThat(Targeting.of("zone", "zone0").and("site", "moghbazar").specificity(MOGHBAZAR)).isEqualTo(2);
        assertThat(Targeting.of("district", "Dhaka").specificity(MOGHBAZAR)).as("case-insensitive").isEqualTo(1);
        assertThat(Targeting.of("zone", "zone1", "zone0").specificity(MOGHBAZAR)).as("one of several").isEqualTo(1);
    }

    @Test
    void a_dimension_that_does_not_match_excludes_the_campaign() {
        assertThat(Targeting.of("zone", "zone1").specificity(MOGHBAZAR)).isEqualTo(-1);
        assertThat(Targeting.of("zone", "zone0").and("site", "venus").specificity(MOGHBAZAR)).isEqualTo(-1);
        assertThat(Targeting.of("site", "moghbazar").specificity(Map.of("zone", "zone0"))).as("the view has no site").isEqualTo(-1);
    }

    @Test
    void wildcards_match_without_counting_and_a_prefix_counts() {
        assertThat(Targeting.of("zone", "*").specificity(MOGHBAZAR)).isZero();
        assertThat(Targeting.of("zone", "*").and("site", "moghbazar").specificity(MOGHBAZAR)).isEqualTo(1);
        assertThat(Targeting.of("gw", "wifi-*").specificity(MOGHBAZAR)).isEqualTo(1);
        assertThat(Targeting.of("gw", "lte-*").specificity(MOGHBAZAR)).isEqualTo(-1);
    }
}
