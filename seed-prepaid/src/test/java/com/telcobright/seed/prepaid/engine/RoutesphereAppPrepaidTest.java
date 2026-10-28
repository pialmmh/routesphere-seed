package com.telcobright.seed.prepaid.engine;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.seed.prepaid.api.Admission;
import com.telcobright.seed.prepaid.api.Final;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.prepaid.internal.PrepaidEngine;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereApp;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2a 3 · ONE engine behind {@code RoutesphereApp}: {@code .prepaid(prepaid)} gives the lane's money as its own Prepaid (no rateAtLevel,
 * no refundPolicy asked; the answers the Prepaid replaces refused when given twice); a lane that gives none keeps working unchanged — the
 * maker builds its Prepaid from its answers, the same engine class either way.
 */
class RoutesphereAppPrepaidTest {

    public static final class Ctx extends RoutesphereContext { public Ctx() {} }

    @Test
    void aLaneWithAPrepaid_statesNoRateNorRefundRule_andItsEngineRunsOnThePrepaid() {
        RoutesphereKit kit = kit();
        RoutesphereFlow<Ctx> flow = lane("AdSupervisor").prepaid(adPrepaid(kit)).build(kit);

        assertThat(flow.money()).isInstanceOf(PrepaidEngine.class);
        assertThat(((PrepaidEngine<Ctx>) flow.money()).rules()).as("the lane's own rules").isNotNull();
        assertThat(((PrepaidEngine<Ctx>) flow.money()).rules().admission()).isEqualTo(Admission.CHARGE_NOW);
    }

    @Test
    void aLaneWithAPrepaid_andTheAnswersItReplaces_isRefused_namingEach() {
        RoutesphereKit kit = kit();

        assertThatThrownBy(() -> lane("AdSupervisor").prepaid(adPrepaid(kit)).rateAtLevel((ctx, tier, partner) -> null)
                .refundPolicy(ctx -> RefundPolicy.callsRule()).billedUnits(ctx -> BigDecimal.ONE).build(kit))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("[AdSupervisor] the lane's Prepaid answers its money (the rate, the renewal, the end) — drop rateAtLevel, refundPolicy,"
                + " billedUnits: two answers to one question");
    }

    @Test
    void aLaneWithoutAPrepaid_stillStatesItsRateAndItsRefundRule() {
        assertThatThrownBy(() -> lane("SmsSupervisor").build(kit()))
            .hasMessageContaining("no rateAtLevel((ctx, tier, partner) -> rate) — what does this tier charge? (or its prepaid(...))")
            .hasMessageContaining("no refundPolicy(ctx -> policy) — what does a session not delivered pay? (or its prepaid(...))");
    }

    @Test
    void aLaneWithoutAPrepaid_runsOnItsAnswers_onTheSameEngine() {
        RoutesphereKit kit = kit();
        RoutesphereFlow<Ctx> flow = lane("SmsSupervisor").rateAtLevel((ctx, tier, partner) -> null).refundPolicy(ctx -> RefundPolicy.callsRule()).build(kit);

        assertThat(flow.money()).isInstanceOf(PrepaidEngine.class);
        assertThat(((PrepaidEngine<Ctx>) flow.money()).rules()).as("the lane's answers, nothing of the Prepaid's own").isNull();
    }

    // ── the scene ───────────────────────────────────────────────────────────

    private static Prepaid<Ctx> adPrepaid(RoutesphereKit kit) {
        return Prepaid.<Ctx>builder("ad").admission(Admission.CHARGE_NOW).rate((view, tier, account) -> BigDecimal.ONE)
            .finalize(view -> view.answered() ? Final.nothing() : Final.refundAll()).build(kit);
    }

    private static RoutesphereApp.Builder<Ctx> lane(String name) {
        return RoutesphereApp.<Ctx>builder(name)
            .serviceGroup(30).identifyRootLevelTenant((facts, find) -> "btcl").identifyPartner((ctx, k, find) -> find.byPartnerId(ctx, 1))
            .route(ctx -> RoutePlan.of(List.of())).delivered((ctx, o) -> ctx.answered()).rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL);
    }

    private static RoutesphereKit kit() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(1, "btcl-client", PartnerType.CUSTOMER);
        return RoutesphereKit.builder().tenants(TenantLookup.of(b.build())).ledger(new InMemoryLedger()).cdr(new RecordingCdrSink())
            .zone(ZoneId.of("Asia/Dhaka")).settings(RoutesphereSettings.defaults()).build();
    }
}
