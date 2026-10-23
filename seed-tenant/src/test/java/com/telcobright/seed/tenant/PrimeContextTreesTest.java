package com.telcobright.seed.tenant;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.dependencies.KitMakers;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LiveTrees;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.tenant.dependencies.PrimeContextTrees;
import com.telcobright.seed.tenant.testkit.ScriptedTrees;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-E items 6 and 8 · seed-tenant's trees as the kit's live trees (R8, R10): every served root loaded from prime-context's tree road before
 * the kit is made (a root that cannot be is refused in words); a root added live is loaded and served; a tier added under a root reloads it;
 * the maker is the one the classpath carries ({@code META-INF/services}). prime-context is a scripted source; no doorbell (no broker here).
 */
class PrimeContextTreesTest {

    private final ScriptedTrees prime = new ScriptedTrees().serve("btcl", root("btcl")).serve("link3", root("link3"));
    private LiveTrees trees;

    @AfterEach
    void close() { if (trees != null) trees.close(); }

    static Tenant root(String name) {
        return new TenantTreeBuilder().root(name).partner(1, name + " client", PartnerType.CUSTOMER).partner(5, "Res5", PartnerType.RESELLER).and()
            .tenant(name + "_res_5", name).partner(501, "Res5 client", PartnerType.CUSTOMER).and().build();
    }

    private static Deployment serving(String... roots) {
        return new Deployment("platform", "ad-sphere", "bed", List.of(roots), RoutesphereSettings.defaults(), ZoneId.of("Asia/Dhaka"), List.of(),
            Map.of(), "10.10.188.2:9092", null, Map.of(), "e1");
    }

    private LiveTrees make(String... roots) {
        trees = new PrimeContextTrees(origin -> prime, false).make(serving(roots), MapBootstrapSource.of(Map.of()));
        return trees;
    }

    @Test
    void everyServedRoot_isLoadedBeforeTheKitIsMade() {
        LiveTrees live = make("btcl", "link3");
        assertThat(live.roots()).containsExactlyInAnyOrder("btcl", "link3");
        assertThat(live.snapshot("link3").orElseThrow().rootDbName()).isEqualTo("link3");
        assertThat(live.tenantByDbName("link3", "link3_res_5")).isPresent();
        assertThat(prime.loads("btcl")).isEqualTo(1);
    }

    @Test
    void aRootPrimeContextCannotLoad_isRefusedInWords() {
        prime.failing("link3");
        assertThatThrownBy(() -> make("btcl", "link3")).hasMessageContaining("the served root 'link3' has no tree");
    }

    @Test
    void aRootAddedLive_isLoadedAndServed_onceEvenWhenAskedTwice() {
        LiveTrees live = make("btcl");
        prime.serve("ccl", root("ccl"));
        live.serve("ccl");
        live.serve("ccl");
        assertThat(live.roots()).containsExactlyInAnyOrder("btcl", "ccl");
        assertThat(live.snapshot("ccl")).isPresent();
        assertThat(prime.loads("ccl")).as("the directory's first load and the serve's own: once per serve, the second serve is nothing").isEqualTo(2);
    }

    @Test
    void aTierAddedUnderARoot_reloadsThatRootOnly() {
        LiveTrees live = make("btcl", "link3");
        int before = prime.loads("link3");
        live.reload("link3");
        assertThat(prime.loads("link3")).isEqualTo(before + 1);
        assertThat(prime.loads("btcl")).isEqualTo(1);
    }

    @Test
    void theClasspathCarriesThisMaker() {
        assertThat(KitMakers.onTheClasspath().trees()).isInstanceOf(PrimeContextTrees.class);
    }
}
