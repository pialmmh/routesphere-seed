package com.telcobright.seed.tenant;

import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.tenant.api.TenantTrees;
import com.telcobright.seed.tenant.testkit.ScriptedTrees;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-O item 1 · One index per snapshot, never per session (the architect's A1): seed-context's swap installs each loaded tree with its
 * {@link RootTree} — its index built then, once — and the trees hand every ask the SAME instance until the next load. The counter reads
 * roots × loads; a root asked by its code or its database name is the same installed tree (one get, no walk of the served roots).
 */
class IndexPerSnapshotTest {

    private TenantTrees trees;

    @AfterEach
    void tearDown() { if (trees != null) trees.close(); }

    @Test
    void a_thousand_asks_of_one_snapshot_get_the_one_tree_installed_at_its_load_and_a_reload_installs_one_more() throws Exception {
        ScriptedTrees primeContext = new ScriptedTrees().serve("btcl", TenantTreesTest.btcl(false)).serve("tele2", TenantTreesTest.tele2());
        trees = TenantTrees.builder().directory(() -> Set.of("btcl", "tele2")).source(primeContext).debounceMs(10_000).build().start();
        assertThat(trees.rootIndexBuilds()).as("two roots, one load each").isEqualTo(2);

        RootTree first = trees.snapshot("btcl").orElseThrow();
        for (int session = 0; session < 1_000; session++) {
            RootTree taken = trees.snapshot("btcl").orElseThrow();
            assertThat(taken).as("session " + session + ": the installed tree").isSameAs(first);
            assertThat(taken.entryOf(8).tenant().getDbName()).isEqualTo("res_45");
        }
        assertThat(trees.rootIndexBuilds()).as("a thousand sessions built nothing").isEqualTo(2);
        assertThat(trees.walks()).isEqualTo(2);

        trees.reload("btcl", "test").get(5, TimeUnit.SECONDS);
        RootTree second = trees.snapshot("btcl").orElseThrow();
        assertThat(second).isNotSameAs(first);
        assertThat(second.version()).isEqualTo(first.version() + 1);
        assertThat(trees.rootIndexBuilds()).as("one root reloaded: one more").isEqualTo(3);
        assertThat(trees.snapshot("tele2").orElseThrow().version()).as("tele2 untouched").isEqualTo(1);
    }

    @Test
    void a_root_asked_by_its_code_or_its_database_name_is_the_same_installed_tree() {
        ScriptedTrees primeContext = new ScriptedTrees().serve("btcl-code", TenantTreesTest.btcl(false));
        trees = TenantTrees.builder().directory(() -> Set.of("btcl-code")).source(primeContext).build().start();
        RootTree byServedId = trees.snapshot("btcl-code").orElseThrow();
        assertThat(trees.snapshot("btcl")).as("by its database name (and its code: the builder names it btcl)").containsSame(byServedId);
        assertThat(trees.rootOfCode("btcl")).map(t -> t.getDbName()).contains("btcl");
        assertThat(trees.root("btcl")).containsSame(byServedId.tree());
        assertThat(trees.snapshot("res_45")).as("a tier is not a root").isEmpty();
        assertThat(trees.snapshot("nobody")).isEmpty();
        assertThat(trees.rootIndexBuilds()).isEqualTo(1);
    }
}
