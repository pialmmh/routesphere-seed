package com.telcobright.seed.sessionflow.samples;

import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.samples.scene.FlowBed;
import com.telcobright.seed.sessionflow.samples.scene.World;
import com.telcobright.seed.sessionflow.samples.voice.CallSupervisor;
import com.telcobright.seed.sessionflow.samples.voice.EslCall;
import com.telcobright.seed.sessionflow.samples.voice.EslCallContext;
import com.telcobright.seed.sessionflow.samples.voice.EslChannel;
import com.telcobright.seed.sessionflow.samples.voice.EslFrame;
import com.telcobright.seed.sessionflow.samples.voice.EslFrames;
import com.telcobright.seed.sessionflow.samples.voice.EslOut;
import com.telcobright.seed.sessionflow.samples.voice.VoiceContext;
import com.telcobright.seed.sessionflow.samples.voice.VoiceSamples;
import com.telcobright.seed.tenant.api.TenantTrees;
import com.telcobright.seed.tenant.testkit.ScriptedTrees;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-O item 1 — the proof of the rule (the architect's A8 a): a root's index is built ONCE per snapshot, by the install, never per session.
 * The voice sample on seed-tenant's real trees: two roots loaded = two builds; a thousand answered calls on btcl's one snapshot build
 * nothing more, and every one of them took the same installed tree; the doorbell's reload of btcl builds exactly one more — and the kit
 * and the engine's stats read the same count.
 */
class OneIndexPerSnapshotTest {

    private final World w = new World();
    private TenantTrees trees;
    private FlowBed<VoiceContext, EslOut, EslFrame> b;

    @AfterEach
    void tearDown() {
        if (b != null) b.close();
        if (trees != null) trees.close();
    }

    @Test
    void a_thousand_sessions_on_one_snapshot_read_one_build_per_root_and_a_reload_reads_one_more() throws Exception {
        ScriptedTrees primeContext = new ScriptedTrees().serve("btcl", World.btclTree(false)).serve("ccl", World.cclTree());
        trees = TenantTrees.builder().directory(() -> Set.of("btcl", "ccl")).source(primeContext).debounceMs(10_000).build().start();
        w.ledger.fund("btcl", 9, "10000.00");
        b = VoiceSamples.bed(w, trees);
        assertEquals(2, trees.rootIndexBuilds(), "two roots, one load each");

        RootTree btclV1 = trees.snapshot("btcl").orElseThrow();
        for (int call = 0; call < 1_000; call++) VoiceSamples.answeredCall(b, "c-" + call, World.ESL_BTCL, "10.0.0.9");

        assertEquals(2, trees.rootIndexBuilds(), "a thousand sessions built no index");
        assertEquals(2, b.flow.kit().rootIndexBuilds(), "the kit reads the trees' count");
        assertEquals(2, b.engine.stats().rootIndexBuilds(), "so do the engine's stats");
        for (int call = 0; call < 1_000; call++) assertSame(btclV1, b.session("c-" + call).tree, "call " + call + " took the installed tree");
        assertEquals(1_000, w.cdrs.published().size());

        trees.reload("btcl", "config_event_loader_btcl").get(5, TimeUnit.SECONDS);
        VoiceSamples.answeredCall(b, "c-after", World.ESL_BTCL, "10.0.0.9");
        assertEquals(3, trees.rootIndexBuilds(), "btcl reloaded: one more build, and the call after it built nothing");
        assertNotSame(btclV1, b.session("c-after").tree);
        assertEquals(btclV1.version() + 1, b.session("c-after").treeVersion);
    }
}
