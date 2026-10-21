package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.seed.sessionflow.samples.scene.FlowBed;
import com.telcobright.seed.sessionflow.samples.scene.World;
import com.telcobright.seed.tenant.api.TenantTrees;
import com.telcobright.seed.tenant.testkit.ScriptedTrees;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §D in a sample: the voice flow on seed-tenant's {@link TenantTrees} — one snapshot per root, reloaded by its doorbell. prime-context
 * adds partner 705 in btcl's res_44: the doorbell for btcl reloads btcl only; a call in the air finishes on the snapshot it started with;
 * the next call walks the new tree; ccl is not reloaded.
 */
class VoiceFlowDoorbellTest {

    private final World w = new World();
    private TenantTrees trees;
    private FlowBed<VoiceContext, EslOut, EslFrame> b;

    @AfterEach
    void tearDown() {
        if (b != null) b.close();
        if (trees != null) trees.close();
    }

    @Test
    void a_doorbell_for_btcl_reloads_btcl_only_a_call_in_the_air_ends_on_its_snapshot_and_the_next_call_sees_the_new_tree() throws Exception {
        ScriptedTrees primeContext = new ScriptedTrees().serve("btcl", World.btclTree(false)).serve("ccl", World.cclTree());
        trees = TenantTrees.builder().directory(() -> Set.of("btcl", "ccl")).source(primeContext).debounceMs(20).build().start();
        Map<String, BigDecimal> rates = new HashMap<>(World.RATES);
        rates.put("res_44#705", new BigDecimal("0.65"));
        Map<String, Integer> partners = new HashMap<>(VoiceFlowTest.PARTNERS);
        partners.put("btcl|10.0.0.5", 705);
        w.ledger.fund("res_44", 705, "100.00");
        VoiceFlow flow = new VoiceFlow(w.kit(World.settings(), trees), partners, rates, VoiceFlowTest.ROUTES);
        b = new FlowBed<>(flow, VoiceContext::new, EslCall.spec(VoiceFlowTest.ESL, EslCallContext::new), new EslChannel(), EslFrames::decode);
        long btclV1 = trees.snapshot("btcl").orElseThrow().version();
        long cclV1 = trees.snapshot("ccl").orElseThrow().version();

        b.frame("c-early", VoiceFlowTest.park("c-early", World.ESL_BTCL, "10.0.0.5", "01711000002"));
        assertEquals("PARTNER_NOT_FOUND", b.session("c-early").endCause, "705 is nobody in btcl's first snapshot");
        b.frame("c-old", VoiceFlowTest.park("c-old", World.ESL_BTCL, "10.0.0.7", "01711000002"));
        b.frame("c-old", EslFrame.answer("c-old", "b-old", "PCMA"));
        assertEquals(btclV1, b.session("c-old").treeVersion, "the call in the air holds btcl's first snapshot");

        primeContext.serve("btcl", World.btclTree(true));                         // prime-context added NewCo; Kafka rings config_event_loader_btcl
        trees.ring("btcl", "config_event_loader_btcl");
        long until = System.currentTimeMillis() + 5_000;
        while (trees.snapshot("btcl").orElseThrow().version() == btclV1 && System.currentTimeMillis() < until) Thread.sleep(10);
        long btclV2 = trees.snapshot("btcl").orElseThrow().version();
        assertTrue(btclV2 > btclV1, "btcl reloaded");

        b.frame("c-new", VoiceFlowTest.park("c-new", World.ESL_BTCL, "10.0.0.5", "01711000002"));
        assertEquals("CONNECTING/TRYING", b.states("c-new"), "the next call walks the new tree: NewCo pays");
        assertEquals(btclV2, b.session("c-new").treeVersion);

        b.frame("c-old", EslFrame.hangup("c-old", "NORMAL_CLEARING"));
        b.frame("c-old", EslFrame.hangupComplete("c-old", "NORMAL_CLEARING"));
        assertEquals("-/-", b.states("c-old"));
        assertEquals(btclV1, b.session("c-old").treeVersion, "it finished on the snapshot it started with");
        assertEquals(List.of("res_44/701", "btcl/44"),
            w.cdrs.of("c-old").get(0).tiers().stream().map(t -> t.tenant + "/" + t.inPartnerId).toList());
        assertEquals(new BigDecimal("99.40"), w.balance("res_44", 701));

        assertEquals(cclV1, trees.snapshot("ccl").orElseThrow().version(), "ccl was not reloaded");
        assertEquals(1, primeContext.loads("ccl"));
        assertEquals(2, primeContext.loads("btcl"));
    }
}
