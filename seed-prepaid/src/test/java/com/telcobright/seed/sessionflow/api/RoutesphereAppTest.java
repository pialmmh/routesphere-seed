package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabFacts;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.SessionStart;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-E item 5 · the builder (R5): a lane's answers as lambdas — the seven forced ones REQUIRED (refused in words), the optional hooks the
 * call's defaults when not given, the base's lookups reachable from the lambdas, the answers sealed once built, a lambda that throws at
 * admission refusing its candidate like an override does.
 */
class RoutesphereAppTest {

    static final Ingress DOOR = Ingress.esl("10.10.199.9", 8021);

    private final InMemoryLedger ledger = new InMemoryLedger().fund("btcl", 1, "10.00");
    private final RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(btcl()))
        .identification(List.of(new IdentificationRule("esl", "10.10.199.9:8021", "btcl"))).ledger(ledger)
        .cdr((id, tiers) -> { }).zone(ZoneId.of("Asia/Dhaka")).build();

    private static Tenant btcl() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(1, "btcl-client", PartnerType.CUSTOMER);
        return b.build();
    }

    /** The seven forced answers and the service group: the smallest lane that may be built. */
    private static RoutesphereApp.Builder<LabContext> complete() {
        return RoutesphereApp.<LabContext>builder("TinySupervisor")
            .serviceGroup(7)
            .identifyRootLevelTenant((facts, find) -> find.byIngress(facts.ingress()))
            .identifyPartner((ctx, candidate, find) -> find.byPartnerId(ctx, ((LabFacts) ctx.facts).partnerId()))
            .rateAtLevel((ctx, tier, partner) -> TierRate.of(new BigDecimal("1.00"), new BigDecimal("1.00"), "BDT", "880"))
            .route(ctx -> RoutePlan.one(new Hop("h-1")))
            .delivered((ctx, outcome) -> ctx.answered())
            .refundPolicy(ctx -> RefundPolicy.callsRule())
            .rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL);
    }

    record Hop(String id) implements com.telcobright.statewalk.session.Route {}

    private static LabContext started(RoutesphereFlow<LabContext> flow, int partnerId) {
        LabContext ctx = new LabContext();
        ctx.facts = new LabFacts("t-" + partnerId, 0, DOOR, partnerId, "8801");
        flow.onStart(ctx, new SessionStart(ctx.facts));
        ctx.candidates = List.of(Candidate.SELF);
        return ctx;
    }

    @Test
    void aMissingForcedAnswer_isRefusedAtBuild_everyOneNamedInWords() {
        assertThatThrownBy(() -> RoutesphereApp.<LabContext>builder("SmsSupervisor").build(kit))
            .hasMessageContaining("[SmsSupervisor] every lane states its rules (R2)")
            .hasMessageContaining("serviceGroup(int)").hasMessageContaining("identifyRootLevelTenant(").hasMessageContaining("identifyPartner(")
            .hasMessageContaining("rateAtLevel(").hasMessageContaining("route(").hasMessageContaining("delivered(")
            .hasMessageContaining("refundPolicy(").hasMessageContaining("rerouteActionFor(");
        assertThatThrownBy(() -> complete().rerouteActionFor(null).build(kit)).hasMessageContaining("no rerouteActionFor(")
            .hasMessageNotContaining("no serviceGroup");
        assertThatThrownBy(() -> RoutesphereApp.builder(" ")).hasMessageContaining("needs its name");
    }

    @Test
    void theBuiltLane_answersWithItsLambdas_andTheBaseRunsIt() {
        RoutesphereFlow<LabContext> flow = complete().build(kit);
        assertThat(flow.name()).isEqualTo("TinySupervisor");
        LabContext ctx = started(flow, 1);
        assertThat(ctx.root).as("identifyRootLevelTenant through the base's lookups").isEqualTo("btcl");
        Verdict verdict = flow.admit(ctx);
        assertThat(verdict.accepted()).isTrue();
        assertThat(ctx.tiers).extracting(t -> t.dbName + "/" + t.partnerId).containsExactly("btcl/1");
        assertThat(ledger.balanceOf("btcl", 1)).isEqualByComparingTo("9.00");
        assertThat(flow.serviceGroup(ctx)).isEqualTo(7);
    }

    @Test
    void anOptionalHookNotGiven_isTheCallsDefault_oneGiven_isTheLanes() {
        RoutesphereFlow<LabContext> plain = complete().build(kit);
        LabContext ctx = started(plain, 1);
        assertThat(plain.selectCandidates(ctx)).containsExactly(Candidate.SELF);
        assertThat(plain.billedUnits(ctx)).isNull();
        assertThat(plain.settleOutcome(ctx)).isEqualTo(SettleOutcome.FAILED);

        RoutesphereFlow<LabContext> own = complete().billedUnits(c -> BigDecimal.TEN).settleOutcome(c -> SettleOutcome.CONSUMED)
            .buildTask(c -> "NO_TASK_TODAY").build(kit);
        LabContext refused = started(own, 1);
        assertThat(own.billedUnits(refused)).isEqualByComparingTo("10");
        assertThat(own.settleOutcome(refused)).isEqualTo(SettleOutcome.CONSUMED);
        assertThat(refused.startRefusal).as("the lane's buildTask refused").isEqualTo("NO_TASK_TODAY");
    }

    @Test
    void theAnswersAreSealedOnceBuilt() {
        RoutesphereApp.Builder<LabContext> b = complete();
        b.build(kit);
        assertThatThrownBy(() -> b.serviceGroup(0)).hasMessageContaining("[TinySupervisor] the application is built: its answers are sealed");
        assertThat(b.build(kit).name()).as("the same answers may make the flow of another kit").isEqualTo("TinySupervisor");
    }

    @Test
    void aLambdaThatThrowsAtAdmission_refusesItsCandidate_likeAnOverride() {
        RoutesphereFlow<LabContext> flow = complete().identifyPartner((ctx, candidate, find) -> { throw new IllegalStateException("boom"); }).build(kit);
        LabContext ctx = started(flow, 1);
        Verdict verdict = flow.admit(ctx);
        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.cause()).isEqualTo(SessionCause.INTERNAL_ERROR);
        assertThat(ledger.journal()).as("nothing reserved").isEmpty();
    }
}
