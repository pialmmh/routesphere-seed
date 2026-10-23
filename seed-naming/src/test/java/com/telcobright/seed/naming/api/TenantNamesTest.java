package com.telcobright.seed.naming.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R9 · a reseller tier's database, as prime-context's RootNaming makes it — the strict rule, and the seed root's legacy one by the bootstrap's word. */
class TenantNamesTest {

    private final TenantNames bed = TenantNames.withSeedRoot("btcl");

    @Test
    void aTierUnderTheRoot_isRootResId_andNested_isParentUnderscoreId() {
        assertThat(bed.tierDb("link3", "link3", 5)).isEqualTo("link3_res_5");
        assertThat(bed.tierDb("link3", null, 5)).as("no parent named = under the root").isEqualTo("link3_res_5");
        assertThat(bed.tierDb("link3", "link3_res_5", 2)).isEqualTo("link3_res_5_2");
        assertThat(bed.tierDb("link3", "link3_res_5_2", 9)).isEqualTo("link3_res_5_2_9");
    }

    @Test
    void theSeedRootKeepsTheLegacyResId_byTheBootstrapsWord_neverByItsName() {
        assertThat(bed.tierDb("btcl", "btcl", 2)).isEqualTo("res_2");
        assertThat(bed.tierDb("btcl", "res_2", 1)).isEqualTo("res_2_1");
        assertThat(TenantNames.strict().tierDb("btcl", "btcl", 2)).as("no seed root named: btcl is strict like any other").isEqualTo("btcl_res_2");
        assertThat(bed.seedRoot()).contains("btcl");
        assertThat(TenantNames.withSeedRoot(" ").seedRoot()).isEmpty();
    }

    @Test
    void theParentOfATier_isFoundByItsRootsPrefix_neverByCountingUnderscores() {
        assertThat(bed.parentOf("link3", "link3_res_5")).contains("link3");
        assertThat(bed.parentOf("link3", "link3_res_5_2")).contains("link3_res_5");
        assertThat(bed.parentOf("link3", "link3_res_5_2_9")).as("three levels: the last id is the tier's own").contains("link3_res_5_2");
        assertThat(bed.parentOf("btcl", "res_2_1")).contains("res_2");
        assertThat(bed.parentOf("btcl", "res_2")).contains("btcl");
        assertThat(bed.parentOf("acme", "res_5")).as("res_5 is never acme's").isEmpty();
        assertThat(bed.parentOf("acme", "acme_res_res_5")).as("a tier of the root acme_res, not of acme").isEmpty();
        assertThat(bed.parentOf("link3", "link3")).as("the root has no parent").isEmpty();
    }

    @Test
    void aStrictRootsTiers_areDigitsOnly_theSeedsAreLoose() {
        assertThat(bed.isTierOf("link3", "link3_res_5_2")).isTrue();
        assertThat(bed.isTierOf("link3", "link3_res_reseller1")).as("a word is a display name, never a database name").isFalse();
        assertThat(bed.isTierOf("link3", "link3_res_05")).as("no leading zero").isFalse();
        assertThat(bed.isTierOf("btcl", "res_x")).as("the seed's loose rule").isTrue();
        assertThat(bed.rootOf("link3_res_5_2", List.of("btcl", "link3"))).contains("link3");
        assertThat(bed.rootOf("res_2", List.of("btcl", "link3"))).contains("btcl");
        assertThat(bed.rootOf("ccl_res_1", List.of("btcl", "link3"))).isEmpty();
    }

    @Test
    void aTierWithoutItsIdOrUnderAStrangerIsRefusedInWords() {
        assertThatThrownBy(() -> bed.tierDb("link3", "link3", 0)).hasMessageContaining("positive");
        assertThatThrownBy(() -> bed.tierDb("link3", "btcl_res_2", 3)).hasMessageContaining("not a tier of the root 'link3'");
        assertThatThrownBy(() -> bed.tierDb(null, null, 3)).hasMessageContaining("root is missing");
    }
}
