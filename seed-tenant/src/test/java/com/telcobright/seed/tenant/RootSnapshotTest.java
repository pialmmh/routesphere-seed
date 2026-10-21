package com.telcobright.seed.tenant;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.tenant.api.TenantTrees;
import com.telcobright.seed.tenant.testkit.ScriptedTrees;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §D · The base's view of the trees: one immutable snapshot per ROOT with its version ({@code TenantTrees.snapshot}). The doorbell for
 * root A reloads A and not B; a snapshot taken before the reload keeps its tree (a running session finishes on it); the next ask sees
 * the new one. Seen red: a snapshot answered by the code's lookup at version 0 (the default) — the version test.
 */
class RootSnapshotTest {

    private TenantTrees trees;

    @AfterEach void close() { if (trees != null) trees.close(); }

    @Test
    void aDoorbellForRootA_reloadsAOnly_aSnapshotTakenBeforeKeepsItsTree_theNextAskSeesTheNew() throws Exception {
        ScriptedTrees script = new ScriptedTrees().serve("btcl", TenantTreesTest.btcl(false)).serve("tele2", TenantTreesTest.tele2());
        trees = TenantTrees.builder().directory(() -> Set.of("btcl", "tele2")).source(script).debounceMs(20).build().start();
        RootTree btclBefore = trees.snapshot("btcl").orElseThrow();
        RootTree tele2Before = trees.snapshot("tele2").orElseThrow();
        assertThat(btclBefore.version()).isGreaterThan(0);
        assertThat(btclBefore.entryOf(46)).isNull();

        script.serve("btcl", TenantTreesTest.btcl(true));                     // res_46 created: prime-context serves it now
        trees.ring("btcl", "config_event_loader_btcl");
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until && trees.snapshot("btcl").orElseThrow().version() == btclBefore.version()) Thread.sleep(10);

        RootTree btclAfter = trees.snapshot("btcl").orElseThrow();
        assertThat(btclAfter.version()).isGreaterThan(btclBefore.version());
        assertThat(btclAfter.entryOf(46)).isNotNull();
        assertThat(btclBefore.entryOf(46)).as("the snapshot a session took keeps its tree").isNull();
        assertThat(btclBefore.tree()).isNotSameAs(btclAfter.tree());
        assertThat(trees.snapshot("tele2").orElseThrow().version()).as("root B was not reloaded").isEqualTo(tele2Before.version());
        assertThat(script.loads("tele2")).isEqualTo(1);
        assertThat(script.loads("btcl")).isEqualTo(2);
    }

    @Test
    void aRootIsFoundByItsServedId_itsCode_orItsDatabaseName_neverAStranger() throws Exception {
        ScriptedTrees script = new ScriptedTrees().serve("btcl", TenantTreesTest.btcl(false));
        trees = TenantTrees.builder().directory(() -> Set.of("btcl")).source(script).debounceMs(20).build().start();
        trees.reload("btcl", "test").get(5, TimeUnit.SECONDS);

        Tenant root = trees.snapshot("btcl").orElseThrow().tree();
        assertThat(root.getDbName()).isEqualTo("btcl");
        assertThat(trees.snapshot("res_45")).as("a tier is not a root").isEmpty();
        assertThat(trees.snapshot("tele2")).isEmpty();
        assertThat(trees.snapshot(null)).isEmpty();
    }
}
