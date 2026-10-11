package com.telcobright.seed.sessionflow;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.dependencies.SessionFlowKit;
import com.telcobright.seed.sessionflow.samples.Scene;
import com.telcobright.seed.sessionflow.samples.VoiceFlow;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.session.AdmissionVerdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The story a stand-in hid (the wifi architect, 2026-10-11): a flow whose rating step names NO account ({@code TierRate.of(...)} —
 * the sample voice flow rates so) is admitted on the testkit's default ledger, which books a tier on its partner as orchestrix's road
 * 16 does — and is REFUSED on the switch's own ledger, which pays from the account the rating step named and knows no "partner's
 * money". A flow that will run on the switch ledger (the WiFi session, the call) tells its stories on
 * {@link InMemoryLedger#accountKeyed()}, so the refusal is seen in a test and not on a box.
 */
class SwitchLedgerShapeStoryTest {

    private static Tenant resellerAndItsClient() {
        return new TenantTreeBuilder()
            .root("btcl")
                .partner(45, "Site Owner", PartnerType.RESELLER)
                .and()
            .tenant("res_45", "btcl")
                .partner(9001, "Subscriber", PartnerType.CUSTOMER)
                .and()
            .build();
    }

    private static AdmissionVerdict admitOn(InMemoryLedger ledger) {
        SessionFlowKit kit = SessionFlowKit.builder()
            .tenants(TenantLookup.of(resellerAndItsClient())).ledger(ledger).cdrSink(new RecordingCdrSink()).sdrSink(record -> { })
            .clock(Clock.system(Scene.DHAKA)).zone(Scene.DHAKA).settings(Scene.settings(4)).build();
        VoiceFlow flow = new VoiceFlow(kit, Map.of("10.0.7.1", 9001),
            Map.of("res_45#9001", new BigDecimal("0.80"), "btcl#45", new BigDecimal("0.50")),
            List.of(new VoiceFlow.Route("017", "GP-trunk", 5)));
        VoiceFlow.Call call = Scene.call("story-1", "10.0.7.1", "01712345678");
        assertThat(flow.preprocess(call)).isNull();
        return flow.admission(call, StepMode.LIVE);
    }

    @Test
    void onTheLedgerThatPicksTheAccountItself_bothTiersPay_fromTheirPartners() {
        InMemoryLedger road16 = new InMemoryLedger().fund("res_45", 9001, "100.00").fund("btcl", 45, "100.00");

        assertThat(admitOn(road16).accepted()).isTrue();

        assertThat(road16.count("reserve")).isEqualTo(2);
    }

    @Test
    void onTheSwitchLedger_aTierWhoseRatingNamedNoAccount_isRefused_andNothingIsHeld() {
        InMemoryLedger switchLedger = InMemoryLedger.accountKeyed().fund("res_45", 9001, "100.00").fund("btcl", 45, "100.00");

        AdmissionVerdict verdict = admitOn(switchLedger);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.rejectCause()).as("the ledger's own word, as the real switch ledger says it").isEqualTo(InMemoryLedger.NO_ACCOUNT);
        assertThat(switchLedger.count("reserve")).isZero();
        assertThat(switchLedger.openReserves()).isZero();
    }
}
