package com.telcobright.seed.callflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.api.CallFlowEngine;
import com.telcobright.seed.callflow.api.CallState;
import com.telcobright.seed.callflow.api.CdrEvent;
import com.telcobright.seed.callflow.api.TierSettlement;
import com.telcobright.seed.callflow.dependencies.CallFlowSettings;
import com.telcobright.seed.callflow.samples.AdFlow;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.samples.SmsFlow;
import com.telcobright.seed.callflow.samples.VoiceFlow;
import com.telcobright.seed.callflow.samples.Wire;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.session.SdrRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whole calls on the pooled machine, for the three sample applications. The same base runs all three; what differs is
 * only what each application said in its own steps. Every test ends by looking at the ledger and at the CDR.
 */
class CallFlowLifecycleTest {

    private final Scene scene = new Scene();
    private final List<CallFlowEngine<?>> engines = new ArrayList<>();

    @AfterEach
    void stopEngines() { engines.forEach(CallFlowEngine::close); }

    private CallFlowEngine<VoiceFlow.Call> voiceEngine(CallFlowSettings settings) {
        return started(CallFlowEngine.of(scene.voice(settings)).child(Wire.TYPE, Wire::new).start());
    }

    private CallFlowEngine<SmsFlow.Message> smsEngine(CallFlowSettings settings) {
        return started(CallFlowEngine.of(scene.sms(settings)).child(Wire.TYPE, Wire::new).start());
    }

    private CallFlowEngine<AdFlow.View> adEngine(CallFlowSettings settings, boolean unshownViewIsCharged) {
        return started(CallFlowEngine.of(scene.ad(settings, unshownViewIsCharged)).child(Wire.TYPE, Wire::new).start());
    }

    private <E extends CallFlowEngine<?>> E started(E engine) {
        engines.add(engine);
        return engine;
    }

    /** Hand the call an event of the wire and wait until the whole call has gone quiet. */
    private static void tell(CallFlowEngine<?> engine, String callId, StatemachineEvent event) throws Exception {
        engine.deliver(callId, event).get(5, TimeUnit.SECONDS);
        engine.awaitSettled(callId, 5, TimeUnit.SECONDS);
    }

    private List<CdrEvent> cdrOf(String callId) throws InterruptedException {
        Scene.await("the CDR of " + callId, () -> !scene.cdrs.of(callId).isEmpty());
        assertThat(scene.cdrs.of(callId)).as("one message per call").hasSize(1);
        return scene.cdrs.of(callId).get(0).tiers();
    }

    private SdrRecord sessionRecordOf(String callId) throws InterruptedException {
        Scene.await("the session record of " + callId, () -> scene.sessionRecords.stream().anyMatch(r -> r.sessionKey().equals(callId)));
        return scene.sessionRecords.stream().filter(r -> r.sessionKey().equals(callId)).findFirst().orElseThrow();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // A voice call
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void aVoiceCall_rings_isAnswered_andHangsUp_everyTierPaysTheMinutesTalked() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(4));
        VoiceFlow.Call call = Scene.call("call-1", "10.0.0.7", "01712345678");

        assertThat(engine.launch(call).launched()).isTrue();
        engine.awaitSettled("call-1", 5, TimeUnit.SECONDS);
        assertThat(engine.stateOf("call-1")).isEqualTo(CallState.ADMITTED);
        tell(engine, "call-1", new Wire.Ring("RINGING"));
        assertThat(engine.stateOf("call-1")).isEqualTo(CallState.RINGING);
        tell(engine, "call-1", new Wire.Answer());
        assertThat(engine.stateOf("call-1")).isEqualTo(CallState.ACTIVE);
        tell(engine, "call-1", new Wire.Hangup(CallCause.NORMAL_CLEARING, 95));       // 95 s = two started minutes

        List<CdrEvent> tiers = cdrOf("call-1");
        assertThat(tiers).hasSize(2);
        CdrEvent leaf = tiers.get(0), top = tiers.get(1);
        assertThat(leaf.tenant).isEqualTo("res_44");
        assertThat(leaf.resellerHierarchy).isEqualTo("btcl > res_44");
        assertThat(leaf.inPartnerId).isEqualTo(701);
        assertThat(leaf.inPartnerCost).isEqualByComparingTo("1.20");
        assertThat(top.tenant).isEqualTo("btcl");
        assertThat(top.resellerHierarchy).isEqualTo("btcl");
        assertThat(top.inPartnerId).isEqualTo(44);
        assertThat(top.inPartnerCost).isEqualByComparingTo("0.80");
        assertThat(tiers).allSatisfy(cdr -> {
            assertThat(cdr.channelCallUuid).isEqualTo("call-1");
            assertThat(cdr.hangupCause).isEqualTo(CallCause.NORMAL_CLEARING);
            assertThat(cdr.answerTime).isNotNull();
            assertThat(cdr.durationSec).isEqualByComparingTo("95");
            assertThat(cdr.outgoingRoute).isEqualTo("GP-trunk");
            assertThat(cdr.outPartnerId).isEqualTo(5);
            assertThat(cdr.terminatingCalledNumber).isEqualTo("8801712345678");
            assertThat(cdr.serviceGroup).as("billing detects a voice call's group").isNull();
            assertThat(cdr.pdd).isNotNull();
        });
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("98.80");
        assertThat(scene.ledger.balanceOf("btcl", 44)).isEqualByComparingTo("99.20");
        assertThat(scene.ledger.openReserves()).isZero();
        assertThat(call.settlements).extracting(TierSettlement::charged).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("1.20"), new BigDecimal("0.80"));
        assertThat(call.settlements.get(0).returned()).as("the call outran its one reserved minute").isNegative();
        assertThat(scene.ledger.timesAsked("settle")).as("every tier is settled exactly once").isEqualTo(2);
        assertThat(scene.ledger.timesAsked("release")).isZero();
        SdrRecord record = sessionRecordOf("call-1");
        assertThat(record.outcome()).isEqualTo(CallState.SUCCEEDED);
        assertThat(call.legsKilled).isTrue();
        Scene.await("the machine is back in the pool", () -> engine.stats().live() == 0);
    }

    @Test
    void aVoiceCallNobodyAnswers_endsAtTheRingingDeadline_andPaysNothing() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(4));
        VoiceFlow.Call call = Scene.call("call-2", "10.0.0.7", "01712345678");

        engine.launch(call);
        engine.awaitSettled("call-2", 5, TimeUnit.SECONDS);
        tell(engine, "call-2", new Wire.Ring("RINGING"));

        List<CdrEvent> tiers = cdrOf("call-2");                                      // the 3 s ringing deadline ends it
        assertThat(tiers).hasSize(2);
        assertThat(tiers).allSatisfy(cdr -> {
            assertThat(cdr.hangupCause).isEqualTo(CallCause.NO_ANSWER);
            assertThat(cdr.answerTime).isNull();
            assertThat(cdr.inPartnerCost).isEqualByComparingTo("0");
        });
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.balanceOf("btcl", 44)).isEqualByComparingTo("100.00");
        assertThat(sessionRecordOf("call-2").outcome()).isEqualTo(CallState.FAILED);
        assertThat(call.legsKilled).as("the legs are hung up on a deadline too").isTrue();
    }

    @Test
    void theCallerHangsUpWhileRinging_theCallFailsWithThatCause_andPaysNothing() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(4));
        engine.launch(Scene.call("call-3", "10.0.0.7", "01712345678"));
        engine.awaitSettled("call-3", 5, TimeUnit.SECONDS);

        tell(engine, "call-3", new Wire.Hangup("ORIGINATOR_CANCEL", 0));

        assertThat(cdrOf("call-3")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo("ORIGINATOR_CANCEL"));
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void aCallDeferredByDesign_endsDeferred_notFailed_andPaysNothing() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(4));
        engine.launch(Scene.call("call-6", "10.0.0.7", "01712345678"));
        engine.awaitSettled("call-6", 5, TimeUnit.SECONDS);

        tell(engine, "call-6", new Wire.Defer("TRY_LATER"));

        assertThat(cdrOf("call-6")).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo("TRY_LATER"));
        assertThat(sessionRecordOf("call-6").outcome()).isEqualTo(CallState.DEFERRED);
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("100.00");
        assertThat(scene.ledger.timesAsked("settle")).isEqualTo(2);
    }

    @Test
    void aCallNobodyIsAdmittedFor_stillWritesOneRecord_onTheTenantItEntered() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(4));

        engine.launch(Scene.call("call-4", "10.9.9.9", "01712345678"));              // nobody owns this source address

        List<CdrEvent> tiers = cdrOf("call-4");
        assertThat(tiers).hasSize(1);
        assertThat(tiers.get(0).tenant).isEqualTo("btcl");
        assertThat(tiers.get(0).hangupCause).isEqualTo(CallCause.PARTNER_NOT_FOUND);
        assertThat(tiers.get(0).inPartnerCost).isEqualByComparingTo("0");
        assertThat(tiers.get(0).answerTime).isNull();
        assertThat(scene.ledger.journal()).isEmpty();
        assertThat(scene.ledger.timesAsked("settle")).as("no tier, nothing to settle").isZero();
    }

    @Test
    void anAnsweredCallThatHitsItsLongestDuration_isChargedForIt_notRefunded() throws Exception {
        CallFlowEngine<VoiceFlow.Call> engine = voiceEngine(Scene.settings(4, 1, 60));    // an answered call may last 1 s
        engine.launch(Scene.call("call-5", "10.0.0.7", "01712345678"));
        engine.awaitSettled("call-5", 5, TimeUnit.SECONDS);
        tell(engine, "call-5", new Wire.Answer());

        List<CdrEvent> tiers = cdrOf("call-5");

        assertThat(tiers).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo(CallCause.MAX_DURATION_REACHED));
        assertThat(tiers.get(0).inPartnerCost).as("one started minute, charged — the deadline is not a refund").isEqualByComparingTo("0.60");
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("99.40");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // An SMS
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void anSms_isDelivered_andEndsAtOnce_theLeafPaysInItsPackageUnits() throws Exception {
        scene.ledger.fund("res_44", 701, "500", "SMS_ea");
        CallFlowEngine<SmsFlow.Message> engine = smsEngine(Scene.settings(4));
        SmsFlow.Message sms = Scene.message("sms-1", "unilever", "x".repeat(200));      // two parts

        engine.launch(sms);
        engine.awaitSettled("sms-1", 5, TimeUnit.SECONDS);
        tell(engine, "sms-1", new Wire.Answer());

        List<CdrEvent> tiers = cdrOf("sms-1");
        CdrEvent leaf = tiers.get(0), top = tiers.get(1);
        assertThat(leaf.inPartnerUom).isEqualTo("SMS_ea");
        assertThat(leaf.packageAmount).as("0.30 a part, two parts, in units").isEqualByComparingTo("0.60");
        assertThat(leaf.inPartnerCost).isEqualByComparingTo("0");
        assertThat(top.inPartnerUom).isEqualTo("BDT");
        assertThat(top.inPartnerCost).isEqualByComparingTo("0.40");
        assertThat(top.packageAmount).isEqualByComparingTo("0");
        assertThat(leaf.outgoingRoute).isEqualTo("smsc-1");
        assertThat(sessionRecordOf("sms-1").outcome()).isEqualTo(CallState.SUCCEEDED);
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("499.40");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void aFailedSubmitGoesToTheNextRoute_andWhenEveryRouteFailsTheMessagePaysNothing() throws Exception {
        scene.ledger.fund("res_44", 701, "500", "SMS_ea");
        CallFlowEngine<SmsFlow.Message> engine = smsEngine(Scene.settings(4));
        SmsFlow.Message retried = Scene.message("sms-2", "unilever", "hello");
        engine.launch(retried);
        engine.awaitSettled("sms-2", 5, TimeUnit.SECONDS);

        tell(engine, "sms-2", new Wire.Fail("SMSC_TIMEOUT"));
        assertThat(engine.stateOf("sms-2")).isEqualTo(CallState.ADMITTED);
        assertThat(retried.attempts).isEqualTo(2);
        tell(engine, "sms-2", new Wire.Answer());

        assertThat(cdrOf("sms-2").get(0).outgoingRoute).isEqualTo("smsc-2");
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("499.70");

        engine.launch(Scene.message("sms-3", "unilever", "hello"));
        engine.awaitSettled("sms-3", 5, TimeUnit.SECONDS);
        tell(engine, "sms-3", new Wire.Fail("SMSC_TIMEOUT"));
        tell(engine, "sms-3", new Wire.Fail("ABSENT_SUBSCRIBER"));

        List<CdrEvent> failed = cdrOf("sms-3");
        assertThat(failed).allSatisfy(cdr -> assertThat(cdr.hangupCause).isEqualTo("ABSENT_SUBSCRIBER"));
        assertThat(failed.get(0).packageAmount).isEqualByComparingTo("0");
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("499.70");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // An ad view
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void anAdView_isShown_completes_andEnds_itsCdrIsServiceGroup30WithTheViewsFacts() throws Exception {
        CallFlowEngine<AdFlow.View> engine = adEngine(Scene.settings(4), true);
        AdFlow.View view = Scene.view("ad-1", "dhaka-zone");

        engine.launch(view);
        engine.awaitSettled("ad-1", 5, TimeUnit.SECONDS);
        tell(engine, "ad-1", new Wire.Ring("SHOWN"));
        assertThat(engine.stateOf("ad-1")).as("an ad has no ringing phase").isEqualTo(CallState.ADMITTED);
        tell(engine, "ad-1", new Wire.Answer());
        tell(engine, "ad-1", new Wire.Hangup(CallCause.NORMAL_CLEARING, 15));

        List<CdrEvent> tiers = cdrOf("ad-1");
        assertThat(tiers).hasSize(2);
        CdrEvent leaf = tiers.get(0);
        assertThat(leaf.serviceGroup).isEqualTo(30);
        assertThat(leaf.tenant).isEqualTo("res_44");
        assertThat(leaf.inPartnerId).as("the second candidate's advertiser paid").isEqualTo(701);
        assertThat(leaf.incomingRoute).isEqualTo("camp-11");
        assertThat(leaf.outgoingRoute).isEqualTo("dhaka-zone");
        assertThat(leaf.originatingCalledNumber).isEqualTo("R100");
        assertThat(leaf.originatingCallingNumber).isEqualTo("AA:BB:CC:00:11:22");
        assertThat(leaf.callerIp).isEqualTo("10.20.0.1");
        assertThat(leaf.matchPrefixCustomer).isEqualTo("R100");
        assertThat(leaf.inPartnerCost).isEqualByComparingTo("0.50");
        assertThat(tiers.get(1).inPartnerCost).isEqualByComparingTo("0.40");
        JsonNode meta = new ObjectMapper().readTree(leaf.additionalMetaData);
        assertThat(meta.get("campaignId").asInt()).isEqualTo(11);
        assertThat(meta.get("zone").asText()).isEqualTo("dhaka-zone");
        assertThat(meta.get("completed").asBoolean()).isTrue();
        assertThat(meta.get("levelIndex").asInt()).isZero();
        assertThat(meta.get("partnerName").asText()).isEqualTo("Unilever");
        assertThat(meta.get("reserveRef").asText()).isEqualTo("ad-1#2#L0");
        assertThat(meta.has("balanceAfter")).isTrue();
    }

    @Test
    void anAdmittedViewNeverShown_keepsItsChargeByTheOwnersRule_orPaysNothingByTheCallsRule() throws Exception {
        CallFlowEngine<AdFlow.View> charging = adEngine(Scene.settings(4), true);
        charging.launch(Scene.view("ad-2", "dhaka-zone"));                               // nobody reports "shown": the 2 s window ends it

        List<CdrEvent> charged = cdrOf("ad-2");
        assertThat(charged).allSatisfy(cdr -> {
            assertThat(cdr.hangupCause).isEqualTo("NOT_SHOWN");
            assertThat(cdr.answerTime).isNull();
        });
        assertThat(charged.get(0).inPartnerCost).isEqualByComparingTo("0.50");
        assertThat(scene.ledger.balanceOf("res_44", 701)).isEqualByComparingTo("99.50");

        CallFlowEngine<AdFlow.View> refunding = adEngine(Scene.settings(4), false);
        refunding.launch(Scene.view("ad-3", "dhaka-zone"));

        assertThat(cdrOf("ad-3").get(0).inPartnerCost).isEqualByComparingTo("0");
        assertThat(scene.ledger.balanceOf("res_44", 701)).as("the second view gave its reserve back").isEqualByComparingTo("99.50");
        assertThat(scene.ledger.openReserves()).isZero();
    }

    @Test
    void aViewRefusedBeforeAdmission_isWrittenOnTheTenantItEntered_withTheOperatorAsItsPartner() throws Exception {
        CallFlowEngine<AdFlow.View> engine = adEngine(Scene.settings(4), true);

        engine.launch(Scene.view("ad-4", "no-such-zone"));

        List<CdrEvent> tiers = cdrOf("ad-4");
        assertThat(tiers).hasSize(1);
        assertThat(tiers.get(0).tenant).isEqualTo("res_44");
        assertThat(tiers.get(0).resellerHierarchy).isEqualTo("btcl > res_44");
        assertThat(tiers.get(0).hangupCause).isEqualTo("NO_RULE");
        assertThat(tiers.get(0).inPartnerId).isEqualTo(5);
        assertThat(tiers.get(0).serviceGroup).isEqualTo(30);
    }

    @Test
    void theLedgerNotTakingASettlement_neverLosesTheCdr_theTierIsMarkedOwed() throws Exception {
        CallFlowEngine<AdFlow.View> engine = adEngine(Scene.settings(4), true);
        engine.launch(Scene.view("ad-5", "dhaka-zone"));
        engine.awaitSettled("ad-5", 5, TimeUnit.SECONDS);
        scene.ledger.failSettlements(true);
        tell(engine, "ad-5", new Wire.Ring("SHOWN"));
        tell(engine, "ad-5", new Wire.Answer());
        tell(engine, "ad-5", new Wire.Hangup(CallCause.NORMAL_CLEARING, 15));

        List<CdrEvent> tiers = cdrOf("ad-5");

        assertThat(tiers.get(0).inPartnerCost).isEqualByComparingTo("0.50");
        assertThat(new ObjectMapper().readTree(tiers.get(0).additionalMetaData).get("settle").asText()).isEqualTo("OWED");
        assertThat(engine.stats().owed()).isEqualTo(2);
    }
}
