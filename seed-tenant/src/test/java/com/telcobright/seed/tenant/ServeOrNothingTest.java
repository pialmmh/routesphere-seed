package com.telcobright.seed.tenant;

import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LiveTrees;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
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
 * P1-G item 1 · a root prime-context does not serve yet (its tree road answers 404 ROOT_NOT_SERVED until tenant-master's switch row is
 * there — PC-0022 §9.3) is never HALF-served: the failed serve leaves nothing of it — not among the served roots, no tree — and a serve
 * once its tree is there serves it.
 */
class ServeOrNothingTest {

    private final ScriptedTrees prime = new ScriptedTrees().serve("btcl", PrimeContextTreesTest.root("btcl")).failing("link3");
    private LiveTrees trees;

    @AfterEach
    void close() { if (trees != null) trees.close(); }

    private LiveTrees servingBtcl() {
        Deployment btcl = new Deployment("btcl", "ad-sphere", "bed", List.of("btcl"), RoutesphereSettings.defaults(), ZoneId.of("Asia/Dhaka"),
            List.of(), Map.of(), "10.10.188.2:9092", null, Map.of(), "e1");
        trees = new PrimeContextTrees(origin -> prime, false).make(btcl, MapBootstrapSource.of(Map.of()));
        return trees;
    }

    @Test
    void aRootWhoseTreeIsNotServedYet_leavesNothingServed() {
        LiveTrees live = servingBtcl();

        assertThatThrownBy(() -> live.serve("link3")).hasMessageContaining("link3");

        assertThat(live.roots()).as("nothing of link3 stays served").containsExactly("btcl");
        assertThat(live.snapshot("link3")).isEmpty();
        prime.recover("link3").serve("link3", PrimeContextTreesTest.root("link3"));        // its tree comes, but nobody served it again
        assertThatThrownBy(() -> live.reload("link3")).as("no cell of link3 left behind to load it unserved").isInstanceOf(IllegalStateException.class);
        assertThat(live.snapshot("link3")).isEmpty();
    }

    @Test
    void theNextServe_onceItsTreeIsThere_servesIt() {
        LiveTrees live = servingBtcl();
        assertThatThrownBy(() -> live.serve("link3")).hasMessageContaining("link3");

        prime.recover("link3").serve("link3", PrimeContextTreesTest.root("link3"));
        live.serve("link3");

        assertThat(live.roots()).containsExactlyInAnyOrder("btcl", "link3");
        assertThat(live.snapshot("link3")).as("its tree, at the next serve").isPresent();
        assertThat(live.tenantByDbName("link3", "link3_res_5")).isPresent();
    }
}
