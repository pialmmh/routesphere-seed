package com.telcobright.seed.naming.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-G item 5 · the seed root's prefix is the bootstrap's word ({@code config.routesphere.naming.seed-prefix}, PC-0022 §9.1: prime-context's
 * {@code primecontext.reseller-db-prefix}); {@link TenantNames#LEGACY_PREFIX} {@code res_} when it says none. A value that is not a name
 * prefix is refused in words.
 */
class SeedPrefixTest {

    @Test
    void theSeedRootsTiers_areNamedUnderTheBootstrapsPrefix() {
        TenantNames names = TenantNames.withSeedRoot("btcl", "rs_");
        assertThat(names.seedPrefix()).isEqualTo("rs_");
        assertThat(names.tierDb("btcl", null, 2)).isEqualTo("rs_2");
        assertThat(names.tierDb("btcl", "rs_2", 1)).isEqualTo("rs_2_1");
        assertThat(names.isTierOf("btcl", "rs_2_1")).isTrue();
        assertThat(names.isTierOf("btcl", "res_2")).as("not under this seed's prefix").isFalse();
        assertThat(names.parentOf("btcl", "rs_2_1")).contains("rs_2");
        assertThat(names.tierDb("link3", null, 5)).as("every other root as before").isEqualTo("link3_res_5");
    }

    @Test
    void noPrefixSaid_isTheLegacyOne() {
        assertThat(TenantNames.withSeedRoot("btcl").seedPrefix()).isEqualTo(TenantNames.LEGACY_PREFIX);
        assertThat(TenantNames.withSeedRoot("btcl", null).tierDb("btcl", null, 2)).isEqualTo("res_2");
        assertThat(TenantNames.withSeedRoot("btcl", " ").seedPrefix()).isEqualTo("res_");
    }

    @Test
    void aValueThatIsNotANamePrefix_isRefusedInWords() {
        assertThatThrownBy(() -> TenantNames.withSeedRoot("btcl", "Res-")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'Res-' is not a name prefix");
        assertThatThrownBy(() -> TenantNames.withSeedRoot("btcl", "res.")).hasMessageContaining("not a name prefix");
        assertThatThrownBy(() -> TenantNames.withSeedRoot("btcl", "_res")).hasMessageContaining("not a name prefix");
    }
}
