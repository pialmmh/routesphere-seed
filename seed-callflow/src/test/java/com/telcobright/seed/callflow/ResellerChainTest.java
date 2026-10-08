package com.telcobright.seed.callflow;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.api.CallFlowEngine;
import com.telcobright.seed.callflow.api.CallState;
import com.telcobright.seed.callflow.api.CdrEvent;
import com.telcobright.seed.callflow.dependencies.CallFlowKit;
import com.telcobright.seed.callflow.dependencies.CallFlowSettings;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.samples.VoiceFlow;
import com.telcobright.seed.callflow.samples.Wire;
import com.telcobright.seed.callflow.spi.TenantLookup;
import com.telcobright.seed.callflow.testkit.InMemoryLedger;
import com.telcobright.seed.callflow.testkit.RecordingCdrSink;
import com.telcobright.seed.callflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.session.AdmissionVerdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resellers at any depth (the owner, 2026-10-03: "starting from the deepest level reseller's partner (leaf node in tree) toward the
 * root of the tree, through all resellers, up to the root/admin tier"). Three tiers:
 *
 * <pre>
 *   btcl                       partner 44  "Alpha Telecom"   — stands for res_44   (typed 4, as the call switch's data types a reseller)
 *     └─ res_44                partner 7   "Beta Net"        — stands for res_44_7 (typed 100, as the ad's data does)
 *          └─ res_44_7         partner 9001 "Leaf Client"    — the caller
 * </pre>
 *
 * Neither reseller is NAMED as its tenant: each is found by the id its tenant's database name ends with, as the call switch finds it
 * ({@code CallAdmissionController.identifyPartnerAtParentLevel}). Every test looks at the money in the ledger.
 */
class ResellerChainTest {

    private final Tenant root = threeTiers();
    private final InMemoryLedger ledger = new InMemoryLedger()
        .fund("res_44_7", 9001, "100.00")
        .fund("res_44", 7, "100.00")
        .fund("btcl", 44, "100.00");
    private final RecordingCdrSink cdrs = new RecordingCdrSink();
    private final List<CallFlowEngine<?>> engines = new ArrayList<>();

    private static Tenant threeTiers() { return threeTiers(true); }

    /** @param withTheMiddleReseller false = nobody stands for {@code res_44_7} in {@code res_44} */
    private static Tenant threeTiers(boolean withTheMiddleReseller) {
        TenantTreeBuilder.TenantSpec middle = new TenantTreeBuilder()
            .root("btcl")
                .partner(44, "Alpha Telecom", PartnerType.CUSTOMER)
                .partner(5, "BTCL Network", PartnerType.CUSTOMER)
                .and()
            .tenant("res_44", "btcl")
                .partner(701, "Unilever", PartnerType.CUSTOMER);
        if (withTheMiddleReseller) middle.partner(7, "Beta Net", PartnerType.RESELLER);
        Tenant root = middle
                .and()
            .tenant("res_44_7", "res_44")
                .partner(9001, "Leaf Client", PartnerType.CUSTOMER)
                .and()
            .build();
        root.getContext().getPartners().get(44).setPartnerType(4);        // the call switch's code for a reseller
        return root;
    }

    private VoiceFlow voice() { return voice(root); }

    private VoiceFlow voice(Tenant tree) {
        CallFlowKit kit = CallFlowKit.builder()
            .tenants(TenantLookup.of(tree)).ledger(ledger).cdrSink(cdrs).sdrSink(record -> { })
            .clock(Clock.system(Scene.DHAKA)).zone(Scene.DHAKA).settings(Scene.settings(4)).build();
        return new VoiceFlow(kit, Map.of("10.0.7.1", 9001),
            Map.of("res_44_7#9001", new BigDecimal("0.80"), "res_44#7", new BigDecimal("0.60"), "btcl#44", new BigDecimal("0.40")),
            List.of(new VoiceFlow.Route("017", "GP-trunk", 5)));
    }

    private static VoiceFlow.Call callOfTheLeaf(String id) { return Scene.call(id, "10.0.7.1", "01712345678"); }

    private AdmissionVerdict admit(VoiceFlow flow, VoiceFlow.Call call) {
        assertThat(flow.preprocess(call)).isNull();
        return flow.admission(call, StepMode.LIVE);
    }

    @AfterEach
    void stopTheEngines() { engines.forEach(CallFlowEngine::close); }

    @Test
    void aCallOfTheDeepestTiersPartner_climbsEveryTier_leafFirst_upToTheRoot() {
        VoiceFlow.Call call = callOfTheLeaf("deep-1");

        assertThat(admit(voice(), call).accepted()).isTrue();

        assertThat(call.levels).extracting(LevelAdmission::getDbName).containsExactly("res_44_7", "res_44", "btcl");
        assertThat(call.levels).extracting(LevelAdmission::getPartnerId).as("the caller, then the reseller standing for the tier below, twice")
            .containsExactly(9001, 7, 44);
        assertThat(call.levels).extracting(LevelAdmission::getDebitReference).containsExactly("deep-1#L0", "deep-1#L1", "deep-1#L2");
        assertThat(call.levels.get(0).getRate()).isEqualByComparingTo("0.80");
        assertThat(call.levels.get(1).getRate()).isEqualByComparingTo("0.60");
        assertThat(call.levels.get(2).getRate()).isEqualByComparingTo("0.40");
        assertThat(ledger.balanceOf("res_44_7", 9001)).as("each tier pays on its own account, at its own rate").isEqualByComparingTo("99.20");
        assertThat(ledger.balanceOf("res_44", 7)).isEqualByComparingTo("99.40");
        assertThat(ledger.balanceOf("btcl", 44)).isEqualByComparingTo("99.60");
        assertThat(call.outgoingRoute).as("the route is resolved once, at the root").isEqualTo("GP-trunk");
    }

    @Test
    void aRefusalAtTheRoot_givesBothTiersBelowTheirMoneyBack() {
        ledger.fund("btcl", 44, "-99.90");                                   // the top reseller is left with 0.10: less than one minute at 0.40
        VoiceFlow.Call call = callOfTheLeaf("deep-2");

        AdmissionVerdict verdict = admit(voice(), call);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).isEqualTo(CallCause.INSUFFICIENT_BALANCE);
        assertThat(ledger.balanceOf("res_44_7", 9001)).as("the leaf reserved first and got it back").isEqualByComparingTo("100.00");
        assertThat(ledger.balanceOf("res_44", 7)).isEqualByComparingTo("100.00");
        assertThat(ledger.openReserves()).isZero();
    }

    @Test
    void aDeactivatedResellerInTheMiddle_refusesTheCall_andKeepsNothing() {
        root.findTenantByDbName("res_44").getContext().getPartners().get(7).setStatus("DEACTIVATED");
        VoiceFlow.Call call = callOfTheLeaf("deep-3");

        AdmissionVerdict verdict = admit(voice(), call);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).isEqualTo(CallCause.PARTNER_DEACTIVATED);
        assertThat(ledger.balanceOf("res_44_7", 9001)).isEqualByComparingTo("100.00");
        assertThat(ledger.balanceOf("btcl", 44)).as("the root was never asked").isEqualByComparingTo("100.00");
        assertThat(ledger.openReserves()).isZero();
    }

    @Test
    void aTierWhoseResellerIsMissingAbove_refusesTheCall() {
        VoiceFlow.Call call = callOfTheLeaf("deep-4");

        AdmissionVerdict verdict = admit(voice(threeTiers(false)), call);       // nobody stands for res_44_7 in res_44

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).isEqualTo(CallCause.PARTNER_NOT_FOUND);
        assertThat(ledger.openReserves()).isZero();
        assertThat(ledger.balanceOf("res_44_7", 9001)).isEqualByComparingTo("100.00");
    }

    @Test
    void aWholeCallThroughThreeTiers_settlesEveryTier_andWritesOneRecordPerTier_theLeafFirst() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = CallFlowEngine.of(voice()).child(Wire.TYPE, Wire::new).start();
        engines.add(engine);

        assertThat(engine.launch(callOfTheLeaf("deep-5")).launched()).isTrue();
        engine.awaitSettled("deep-5", 5, TimeUnit.SECONDS);
        assertThat(engine.stateOf("deep-5")).isEqualTo(CallState.ADMITTED);
        engine.deliver("deep-5", new Wire.Answer()).get(5, TimeUnit.SECONDS);
        engine.awaitSettled("deep-5", 5, TimeUnit.SECONDS);
        engine.deliver("deep-5", new Wire.Hangup(CallCause.NORMAL_CLEARING, 95)).get(5, TimeUnit.SECONDS);   // two started minutes
        engine.awaitSettled("deep-5", 5, TimeUnit.SECONDS);

        Scene.await("the CDR of deep-5", () -> !cdrs.of("deep-5").isEmpty());
        assertThat(cdrs.of("deep-5")).as("ONE message for the call").hasSize(1);
        List<CdrEvent> tiers = cdrs.of("deep-5").get(0).tiers();
        assertThat(tiers).extracting(cdr -> cdr.tenant).containsExactly("res_44_7", "res_44", "btcl");
        assertThat(tiers).extracting(cdr -> cdr.resellerHierarchy).containsExactly("btcl > res_44 > res_44_7", "btcl > res_44", "btcl");
        assertThat(tiers).extracting(cdr -> cdr.inPartnerId).containsExactly(9001, 7, 44);
        assertThat(tiers.get(0).inPartnerCost).isEqualByComparingTo("1.60");
        assertThat(tiers.get(1).inPartnerCost).isEqualByComparingTo("1.20");
        assertThat(tiers.get(2).inPartnerCost).isEqualByComparingTo("0.80");
        assertThat(ledger.balanceOf("res_44_7", 9001)).isEqualByComparingTo("98.40");
        assertThat(ledger.balanceOf("res_44", 7)).isEqualByComparingTo("98.80");
        assertThat(ledger.balanceOf("btcl", 44)).isEqualByComparingTo("99.20");
        assertThat(ledger.timesAsked("settle")).as("every tier is settled exactly once").isEqualTo(3);
        assertThat(ledger.openReserves()).isZero();
    }
}
