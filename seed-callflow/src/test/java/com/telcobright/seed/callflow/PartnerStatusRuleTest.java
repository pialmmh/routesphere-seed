package com.telcobright.seed.callflow;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.samples.VoiceFlow;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.session.AdmissionVerdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4's status check is the call switch's: only the word DEACTIVATED, in any case, refuses a partner (ARCH-0051 Q2); any other
 * status — SUSPENDED, PENDING, a word of the portal's own — or none passes, and the tier goes on to its money.
 */
class PartnerStatusRuleTest {

    private final Scene scene = new Scene();
    private final VoiceFlow voice = scene.voice(Scene.settings(4));

    private Partner partner(int id) {
        Tenant tenant = voice.kit().tenants().tenantOfPartner("btcl", id).orElseThrow();
        return tenant.getContext().getPartners().get(id);
    }

    private AdmissionVerdict admit(String id, String sourceIp) {
        VoiceFlow.Call call = Scene.call(id, sourceIp, "01712345678");
        assertThat(voice.preprocess(call)).isNull();
        return voice.admit(call, StepMode.LIVE);
    }

    @Test
    void aPartnerInAnyStatusButDeactivated_passes_andMeetsItsMoneyNext() {
        partner(702).setStatus("SUSPENDED");                                       // Poorco: no money, so the refusal that follows is the ledger's

        AdmissionVerdict verdict = admit("ps-1", "10.0.0.2");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).as("the status passed; the money refused").isEqualTo(CallCause.INSUFFICIENT_BALANCE);
    }

    @Test
    void aPartnerWithNoStatus_passes() {
        partner(701).setStatus(null);

        assertThat(admit("ps-2", "10.0.0.7").accepted()).isTrue();
    }

    @Test
    void deactivated_inAnyCase_refuses() {
        partner(701).setStatus("deactivated");

        AdmissionVerdict verdict = admit("ps-3", "10.0.0.7");

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).isEqualTo(CallCause.PARTNER_DEACTIVATED);
        assertThat(scene.ledger.count("reserve")).as("refused before any money moved").isZero();
    }

    @Test
    void theScenesClosedPartner_isStillRefused_atTheLeaf() {
        AdmissionVerdict verdict = admit("ps-4", "10.0.0.4");                        // 704 "Closed", deactivated by the builder

        assertThat(verdict.rejectCause()).isEqualTo(CallCause.PARTNER_DEACTIVATED);
    }
}
