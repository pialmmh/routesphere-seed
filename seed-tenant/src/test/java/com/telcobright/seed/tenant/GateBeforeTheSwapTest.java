package com.telcobright.seed.tenant;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LiveTrees;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.tenant.dependencies.PrimeContextTrees;
import com.telcobright.seed.tenant.testkit.ScriptedTrees;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-G item 2 · seed-tenant's trees ask the kit's gate with every NEW tree before it is swapped in — whatever loads it (the doorbell's
 * ring, a reload, the backstop): the gate sees the tree no session sees yet; a gate that throws keeps the last tree.
 */
class GateBeforeTheSwapTest {

    private final ScriptedTrees prime = new ScriptedTrees().serve("btcl", PrimeContextTreesTest.root("btcl"));
    private LiveTrees live;

    @BeforeEach
    void up() {
        Deployment btcl = new Deployment("btcl", "ad-sphere", "bed", List.of("btcl"), RoutesphereSettings.defaults(), ZoneId.of("Asia/Dhaka"),
            List.of(), Map.of(), "10.10.188.2:9092", null, Map.of(), "e1");
        live = new PrimeContextTrees(origin -> prime, false).make(btcl, MapBootstrapSource.of(Map.of()));
    }

    @AfterEach
    void close() { live.close(); }

    /** btcl with a NEW reseller tier btcl_res_7 beside btcl_res_5. */
    static Tenant btclWithTier7() {
        return new TenantTreeBuilder().root("btcl").partner(1, "btcl client", PartnerType.CUSTOMER).partner(5, "Res5", PartnerType.RESELLER)
            .partner(7, "Res7", PartnerType.RESELLER).and()
            .tenant("btcl_res_5", "btcl").partner(501, "Res5 client", PartnerType.CUSTOMER).and()
            .tenant("btcl_res_7", "btcl").partner(701, "Res7 client", PartnerType.CUSTOMER).and().build();
    }

    private static boolean holdsTier7(Tenant tree) { return tree.getTenantIndex().values().stream().anyMatch(t -> "btcl_res_7".equals(t.getDbName())); }

    @Test
    void theGateSeesEachNewTree_beforeASessionCan() {
        List<String> seen = new CopyOnWriteArrayList<>();
        live.gateEachNewTree((root, tree) -> seen.add(root + " new tree holds res_7: " + holdsTier7(tree) + ", shown: " + live.tenantByDbName("btcl", "btcl_res_7").isPresent()));
        prime.serve("btcl", btclWithTier7());

        live.reload("btcl");

        assertThat(seen).containsExactly("btcl new tree holds res_7: true, shown: false");
        assertThat(live.tenantByDbName("btcl", "btcl_res_7")).isPresent();
    }

    @Test
    void aGateThatThrows_keepsTheLastTree() {
        live.gateEachNewTree((root, tree) -> { throw new IllegalStateException("the ledger of " + root + " cannot take its new tier yet (scripted)"); });
        prime.serve("btcl", btclWithTier7());

        assertThatThrownBy(() -> live.reload("btcl")).hasMessageContaining("btcl");

        assertThat(live.tenantByDbName("btcl", "btcl_res_7")).as("not shown").isEmpty();
        assertThat(live.tenantByDbName("btcl", "btcl_res_5")).as("the last tree stays").isPresent();
    }
}
