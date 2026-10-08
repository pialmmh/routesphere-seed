package com.telcobright.seed.callflow;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.api.CallState;
import com.telcobright.seed.callflow.api.DryRun;
import com.telcobright.seed.callflow.api.TierRate;
import com.telcobright.seed.callflow.dependencies.CallFlowKit;
import com.telcobright.seed.callflow.samples.AdFlow;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.samples.VoiceFlow;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.session.AdmissionVerdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admission chain of the base, with no machine: the steps as a call meets them, for a voice call and for an ad view.
 * Every test looks at the money in the ledger, not at what the pipeline says it did.
 */
class CallFlowAdmissionTest {

    private final Scene scene = new Scene();
    private final VoiceFlow voice = scene.voice(Scene.settings(4));
    private final AdFlow ad = scene.ad(Scene.settings(4), true);

    private AdmissionVerdict admit(VoiceFlow.Call call) {
        assertThat(voice.preprocess(call)).isNull();
        return voice.admission(call, StepMode.LIVE);
    }

    private AdmissionVerdict admit(AdFlow.View view) {
        assertThat(ad.preprocess(view)).isNull();
        return ad.admission(view, StepMode.LIVE);
    }

    private static BigDecimal money(String amount) { return new BigDecimal(amount); }

    // ── the tenant chain ────────────────────────────────────────────────────

    @Test
    void everyTierReservesOnItsOwnRate_theLeafFirst() {
        VoiceFlow.Call call = Scene.call("c-1", "10.0.0.7", "01712345678");

        AdmissionVerdict verdict = admit(call);

        assertThat(verdict.accepted()).isTrue();
        assertThat(call.levels).hasSize(2);
        LevelAdmission leaf = call.levels.get(0), top = call.levels.get(1);
        assertThat(leaf.getDbName()).isEqualTo("res_44");
        assertThat(leaf.getPartnerId()).isEqualTo(701);
        assertThat(leaf.getRate()).isEqualByComparingTo("0.60");
        assertThat(leaf.getDebitReference()).isEqualTo("c-1#L0");
        assertThat(top.getDbName()).isEqualTo("btcl");
        assertThat(top.getPartnerId()).isEqualTo(44);
        assertThat(top.getRate()).isEqualByComparingTo("0.40");
        assertThat(top.getDebitReference()).isEqualTo("c-1#L1");
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("99.40");
        assertThat(scene.ledger.balanceOf("btcl", 44)).isEqualByComparingTo("99.60");
        assertThat(leaf.getBalanceBefore()).isEqualByComparingTo("100.00");
        assertThat(leaf.getBalanceAfter()).isEqualByComparingTo("99.40");
    }

    @Test
    void theRouteAndTheDigitRulesComeFromTheRootTenant() {
        VoiceFlow.Call call = Scene.call("c-2", "10.0.0.7", "01812345678");

        assertThat(admit(call).accepted()).isTrue();

        assertThat(call.outgoingRoute).isEqualTo("Robi-trunk");
        assertThat(call.outPartnerId).isEqualTo(5);
        assertThat(call.terminatingCalledNumber).isEqualTo("8801812345678");
        assertThat(call.entryTenant.getDbName()).isEqualTo("res_44");
        assertThat(call.partner.getIdPartner()).isEqualTo(701);
    }

    @Test
    void aDirectCustomerOfTheRootHasOneTier() {
        VoiceFlow.Call call = Scene.call("c-3", "10.0.0.9", "01712345678");

        assertThat(admit(call).accepted()).isTrue();

        assertThat(call.levels).hasSize(1);
        assertThat(call.levels.get(0).getDbName()).isEqualTo("btcl");
        assertThat(scene.ledger.balanceOf("btcl", 9)).isEqualByComparingTo("99.50");
    }

    @Test
    void aLaterTiersRefusalGivesTheEarlierTiersBack() {
        scene.ledger.fund("btcl", 44, "0.10");                       // the reseller cannot pay its tier
        VoiceFlow.Call call = Scene.call("c-4", "10.0.0.7", "01712345678");

        AdmissionVerdict verdict = admit(call);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).isEqualTo(CallCause.INSUFFICIENT_BALANCE);
        assertThat(call.levels).isEmpty();
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.openReserves()).isZero();
        assertThat(scene.ledger.count("release")).isEqualTo(1);
    }

    @Test
    void aLedgerFaultIsNeverABalanceCause_andGivesBack() {
        scene.ledger.faultOn("btcl", 44);
        VoiceFlow.Call call = Scene.call("c-5", "10.0.0.7", "01712345678");

        AdmissionVerdict verdict = admit(call);

        assertThat(verdict.rejectCause()).isEqualTo(CallCause.BILLING_SYSTEM_ERROR);
        assertThat(call.systemFault).isEqualTo(CallCause.BILLING_SYSTEM_ERROR);
        assertThat(CallCause.isSystemFault(verdict.rejectCause())).isTrue();
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void theLedgersOwnRefusalIsTheCause() {
        scene.ledger.refuseOn("res_44", 701, "PARTNER_INACTIVE");

        AdmissionVerdict verdict = admit(Scene.call("c-6", "10.0.0.7", "01712345678"));

        assertThat(verdict.rejectCause()).isEqualTo("PARTNER_INACTIVE");
    }

    @Test
    void aRefusalAfterTheChainStillGivesEveryReserveBack() {
        VoiceFlow.Call noRoute = Scene.call("c-7", "10.0.0.7", "01912345678");        // no route serves 019

        assertThat(admit(noRoute).rejectCause()).isEqualTo(CallCause.NO_ROUTE);

        assertThat(scene.ledger.count("reserve")).isEqualTo(2);
        assertThat(scene.ledger.count("release")).isEqualTo(2);
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.balanceOf("btcl", 44)).isEqualByComparingTo("100.00");
    }

    @Test
    void theRootsRulesRefuseAfterTheLeafReserved_andTheLeafGetsItBack() {
        VoiceFlow.Call abroad = Scene.call("c-8", "10.0.0.7", "0044123456");

        assertThat(admit(abroad).rejectCause()).isEqualTo("DIGIT_FILTER_DENIED");

        assertThat(scene.ledger.count("reserve")).isEqualTo(1);
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void anUnknownSourceAndADeactivatedPartnerAreRefusedBeforeAnyMoneyMoves() {
        assertThat(admit(Scene.call("c-9", "10.9.9.9", "01712345678")).rejectCause()).isEqualTo(CallCause.PARTNER_NOT_FOUND);
        assertThat(admit(Scene.call("c-10", "10.0.0.4", "01712345678")).rejectCause()).isEqualTo(CallCause.PARTNER_DEACTIVATED);

        assertThat(scene.ledger.journal()).isEmpty();
    }

    @Test
    void theRequestMustNameATenantThisProcessServes() {
        VoiceFlow.Call call = Scene.call("c-11", "10.0.0.7", "01712345678");
        call.tenantName = "nobody";

        assertThat(voice.preprocess(call)).isEqualTo(CallCause.TENANT_UNAVAILABLE);
    }

    // ── the partner's channel cap ───────────────────────────────────────────

    @Test
    void thePartnersCapRefusesTheSecondCall_untilTheFirstEnds() {
        VoiceFlow.Call first = Scene.call("c-20", "10.0.0.3", "01712345678");       // partner 703: one call at a time
        VoiceFlow.Call second = Scene.call("c-21", "10.0.0.3", "01712345678");

        assertThat(admit(first).accepted()).isTrue();
        assertThat(admit(second).rejectCause()).isEqualTo(CallCause.CHANNEL_LIMIT_REACHED);

        first.outcome = CallState.FAILED;
        voice.close(first, CallState.FAILED, Scene.NO_MACHINE);
        VoiceFlow.Call third = Scene.call("c-22", "10.0.0.3", "01712345678");
        assertThat(admit(third).accepted()).isTrue();
    }

    @Test
    void aRefusedCallNeverKeepsItsSlot() {
        scene.ledger.fund("res_44", 703, "0.00");
        VoiceFlow.Call broke = Scene.call("c-23", "10.0.0.3", "01712345678");
        assertThat(admit(broke).rejectCause()).isEqualTo(CallCause.INSUFFICIENT_BALANCE);

        scene.ledger.fund("res_44", 703, "5.00");
        assertThat(admit(Scene.call("c-24", "10.0.0.3", "01712345678")).accepted()).isTrue();
    }

    // ── candidates ──────────────────────────────────────────────────────────

    @Test
    void theSecondCandidatePaysWhenTheFirstCannot_andItsReserveIsNotAReplayOfTheFirst() {
        AdFlow.View view = Scene.view("v-1", "dhaka-zone");             // campaign 10 (702, no money), then campaign 11 (701)

        AdmissionVerdict verdict = admit(view);

        assertThat(verdict.accepted()).isTrue();
        assertThat(view.candidateIndex).isEqualTo(1);
        assertThat(view.candidatesTried).isEqualTo(2);
        assertThat(view.playing.id()).isEqualTo(11);
        assertThat(view.incomingRoute).isEqualTo("camp-11");
        assertThat(view.lastRefusal).isEqualTo(CallCause.INSUFFICIENT_BALANCE);
        assertThat(view.levels).extracting(LevelAdmission::getDebitReference).containsExactly("v-1#2#L0", "v-1#2#L1");
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("99.50");
        assertThat(scene.ledger.balanceOf("btcl", 44)).isEqualByComparingTo("99.60");
    }

    @Test
    void afterALedgerFaultOnlyTheHouseAdIsTried_andItIsFree() {
        scene.ledger.faultOn("res_44", 702);
        AdFlow.View view = Scene.view("v-2", "dhaka-zone");

        AdmissionVerdict verdict = admit(view);

        assertThat(verdict.accepted()).isTrue();
        assertThat(view.playing.houseAd()).isTrue();
        assertThat(view.candidatesTried).isEqualTo(2);                    // campaign 11 was skipped, never tried
        assertThat(view.levels).hasSize(2);
        assertThat(view.levels).allSatisfy(level -> assertThat(level.getTotalReserved()).isEqualByComparingTo("0"));
        assertThat(scene.ledger.count("reserve")).isZero();
    }

    @Test
    void aHouseAdWithNoPartnerHasNoTier() {
        AdFlow.View view = Scene.view("v-3", "house-zone");

        assertThat(admit(view).accepted()).isTrue();
        assertThat(view.levels).isEmpty();
        assertThat(view.partner).isNull();
    }

    @Test
    void whenNoCandidateCanPay_theApplicationNamesTheCause() {
        AdFlow.View view = Scene.view("v-4", "poor-zone");

        AdmissionVerdict verdict = admit(view);

        assertThat(verdict.rejectCause()).isEqualTo("NO_FUNDED_CAMPAIGN");
        assertThat(view.lastRefusal).isEqualTo(CallCause.INSUFFICIENT_BALANCE);
        assertThat(view.entryTenant.getDbName()).isEqualTo("res_44");     // kept for the failed call's CDR
    }

    @Test
    void theQuotaIsClaimedLast_aRefusalThereGivesBackAndTheNextCandidateIsTried() {
        scene.ledger.fund("res_44", 702, "10.00");
        ad.quota(10, 0);                                                 // campaign 10 has no view left
        AdFlow.View view = Scene.view("v-5", "dhaka-zone");

        assertThat(admit(view).accepted()).isTrue();

        assertThat(view.playing.id()).isEqualTo(11);
        assertThat(view.lastRefusal).isEqualTo("QUOTA_EXHAUSTED");
        assertThat(scene.ledger.balanceOf("res_44", 702)).isEqualByComparingTo("10.00");
        assertThat(scene.ledger.openReserves()).isEqualTo(2);            // only the playing candidate's two tiers
    }

    // ── the dry run, and a step that throws ─────────────────────────────────

    @Test
    void aDryRunRatesEveryTierAndMovesNothing() {
        ad.quota(11, 1);
        scene.ledger.fund("res_44", 702, "10.00");
        ad.quota(10, 5);

        DryRun dryRun = ad.simulate(Scene.view("v-6", "dhaka-zone"));

        assertThat(dryRun.admitted()).isTrue();
        assertThat(dryRun.levels()).hasSize(2);
        assertThat(dryRun.levels().get(0).getRate()).isEqualByComparingTo("0.50");
        assertThat(dryRun.incomingRoute()).isEqualTo("camp-10");
        assertThat(dryRun.trace()).anySatisfy(line -> assertThat(String.valueOf(line)).contains("IDENTIFY_ENTRY_PARTNER"));
        assertThat(scene.ledger.journal()).isEmpty();
        assertThat(ad.simulate(Scene.view("v-7", "dhaka-zone")).admitted()).isTrue();     // the quota of 5 was not touched
        assertThat(scene.cdrs.count()).isZero();
    }

    @Test
    void withDebugOnEveryStepIsTraced_withDebugOffOnlyARefusalIsNoted() {
        VoiceFlow traced = scene.voice(Scene.settings(2).withDebug(true));
        VoiceFlow.Call loud = Scene.call("c-40", "10.0.0.7", "01712345678");
        assertThat(traced.preprocess(loud)).isNull();
        assertThat(traced.admission(loud, StepMode.LIVE).accepted()).isTrue();
        VoiceFlow.Call quiet = Scene.call("c-41", "10.0.0.9", "01712345678");
        assertThat(admit(quiet).accepted()).isTrue();
        VoiceFlow.Call refused = Scene.call("c-42", "10.0.0.7", "01912345678");
        assertThat(admit(refused).accepted()).isFalse();

        assertThat(loud.history.snapshot()).extracting(line -> line.cause().split(" ")[0])
            .contains("RESOLVE_TENANT", "BUILD_TASK", "IDENTIFY_ENTRY_PARTNER", "CHECK_PARTNER", "RATE", "IDENTIFY_PARTNER", "ROOT_RULES", "RESOLVE_ROUTE");
        assertThat(quiet.history.snapshot()).as("an admitted call leaves no line when debug is off").isEmpty();
        assertThat(refused.history.snapshot()).hasSize(1);
        assertThat(refused.history.snapshot().get(0).cause()).contains("refused: NO_ROUTE");
    }

    @Test
    void aStepThatThrowsEndsWithInternalError_andGivesBack() {
        CallFlowKit kit = scene.kit(Scene.settings(2));
        VoiceFlow broken = new VoiceFlow(kit, Map.of("10.0.0.7", 701),
            Map.of("res_44#701", money("0.60"), "btcl#44", money("0.40")), List.of(new VoiceFlow.Route("017", "GP-trunk", 5))) {
            @Override protected TierRate rateAtLevel(Call call, Tenant tier, Partner partner, int levelIndex) {
                if (levelIndex == 1) throw new IllegalStateException("the rate cache is gone");
                return super.rateAtLevel(call, tier, partner, levelIndex);
            }
        };
        VoiceFlow.Call call = Scene.call("c-30", "10.0.0.7", "01712345678");
        assertThat(broken.preprocess(call)).isNull();

        AdmissionVerdict verdict = broken.admission(call, StepMode.LIVE);

        assertThat(verdict.rejectCause()).isEqualTo(CallCause.INTERNAL_ERROR);
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.openReserves()).isZero();
    }
}
