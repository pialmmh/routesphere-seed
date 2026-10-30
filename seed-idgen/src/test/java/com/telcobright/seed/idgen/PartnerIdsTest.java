package com.telcobright.seed.idgen;

import com.telcobright.seed.idgen.api.IdServiceUnavailable;
import com.telcobright.seed.idgen.api.PartnerIds;
import com.telcobright.seed.idgen.testkit.InMemoryIdSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The owner's rule of 2026-10-09 on the base's id port: a partner id is unique within the tenant's tree, above every id the tree holds —
 * the tenant now the caller's word on every call (2026-10-30: one id service per installation, the tenant mandatory).
 */
class PartnerIdsTest {

    @Test
    void oneCounterPerTree_namedByTheRoot() {
        assertThat(PartnerIds.entityOf("btcl")).isEqualTo("partner.btcl");
        assertThat(PartnerIds.entityOf("res_44")).isEqualTo("partner.res_44");
        assertThatThrownBy(() -> PartnerIds.entityOf(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PartnerIds.entityOf("btcl/x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manyCreatorsAtOnce_onEveryShard_neverTheSameId_neverOneTheTreeHolds() throws Exception {
        PartnerIds partners = new PartnerIds(new InMemoryIdSource(3));
        Set<Integer> minted = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        IntStream.range(0, 600).forEach(i -> pool.submit(() -> minted.add(partners.next("btcl", "btcl", 5000))));
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(minted).hasSize(600).allSatisfy(id -> assertThat(id).isGreaterThan(5000));
        assertThat(minted.stream().map(id -> id % 3).distinct()).as("every shard minted").hasSize(3);
    }

    @Test
    void twoTrees_countAlone() {
        PartnerIds partners = new PartnerIds(new InMemoryIdSource());

        assertThat(List.of(partners.next("btcl", "btcl", 0), partners.next("btcl", "btcl", 0), partners.next("ccl", "ccl", 0))).containsExactly(1, 2, 1);
    }

    @Test
    void theSameRootWordUnderTwoTenants_isTwoCounters_theTenantIsTheCallersWord() {
        InMemoryIdSource cluster = new InMemoryIdSource();
        PartnerIds partners = new PartnerIds(cluster);

        assertThat(List.of(partners.next("btcl", "wroot", 100), partners.next("acme", "wroot", 0), partners.next("btcl", "wroot", 100))).containsExactly(101, 1, 102);
        assertThat(cluster.entitiesOf("btcl")).containsExactly("partner.wroot");
        assertThat(cluster.entitiesOf("acme")).containsExactly("partner.wroot");
    }

    @Test
    void aBlankOrIllFormedTenant_isRefusedInWords_beforeAnyCall() {
        InMemoryIdSource cluster = new InMemoryIdSource();
        PartnerIds partners = new PartnerIds(cluster);

        for (String bad : new String[]{null, "", " ", "BTCL", "bt-cl", "b"}) {
            assertThatThrownBy(() -> partners.next(bad, "btcl", 0)).as("'" + bad + "'")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant-master's tenant id").hasMessageContaining("no default");
        }
        assertThat(cluster.calls()).isZero();
    }

    @Test
    void aShardDown_theOthersCarryOn_allDown_noIdAtAll() {
        InMemoryIdSource cluster = new InMemoryIdSource(2).down(1);
        PartnerIds partners = new PartnerIds(cluster);

        assertThat(List.of(partners.next("btcl", "btcl", 10), partners.next("btcl", "btcl", 10))).containsExactly(12, 14);
        cluster.down(2);
        assertThatThrownBy(() -> partners.next("btcl", "btcl", 10)).isInstanceOf(IdServiceUnavailable.class);
    }
}
