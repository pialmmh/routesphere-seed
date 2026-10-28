package com.telcobright.seed.prepaid.api;

import com.telcobright.rtc.domainmodel.PartnerType;
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
import com.telcobright.statewalk.session.SessionTimings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2a 2b · The Prepaid builder (design §10): a required part missing is refused in words, naming each; a windowed Prepaid states the
 * kit's own cadence (one cadence); a per-event one holds no window; the rules are sealed once built; a Prepaid serves the kit it was
 * built on. A lane that gives none runs on its own answers.
 */
class PrepaidBuilderTest {

    public static final class Ctx extends RoutesphereContext { public Ctx() {} }

    static final RoutesphereSettings CALL_CADENCE = new RoutesphereSettings(8, 2, 12_000, new SessionTimings(2, 2, 5, 10_800), 60, 58, 0, false, 200);
    static final RoutesphereSettings NO_RENEWAL = CALL_CADENCE.withReservePeriodSec(0).withReserveInitialDelaySec(0);

    @Test
    void everyRequiredPartMissing_isRefusedInWords_namingEach() {
        assertThatThrownBy(() -> Prepaid.<Ctx>builder("call").build(kit(CALL_CADENCE)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("[call] the Prepaid has no admission(HOLD_WINDOW | CHARGE_NOW)")
            .hasMessageContaining("no rate((ctx, tier, account) -> amount)")
            .hasMessageContaining("no finalize(ctx -> Final)");
    }

    @Test
    void aWindowedPrepaid_needsItsWindowAndItsFirstRenewal() {
        assertThatThrownBy(() -> rated(Admission.HOLD_WINDOW).build(kit(CALL_CADENCE)))
            .hasMessageContaining("no window(duration)").hasMessageContaining("no firstRenewal(duration)");
    }

    @Test
    void aWindowedPrepaid_statesTheKitsOwnCadence() {
        assertThatThrownBy(() -> rated(Admission.HOLD_WINDOW).window(Duration.ofSeconds(30)).firstRenewal(Duration.ofSeconds(58)).build(kit(CALL_CADENCE)))
            .hasMessageContaining("the Prepaid's window is 30 s, first renewal 58 s, but the kit renews every 60 s, first after 58 s — one cadence");
        assertThat(rated(Admission.HOLD_WINDOW).window(Duration.ofSeconds(60)).firstRenewal(Duration.ofSeconds(58)).build(kit(CALL_CADENCE)).admission())
            .isEqualTo(Admission.HOLD_WINDOW);
    }

    @Test
    void aPerEventPrepaid_holdsNoWindow() {
        assertThatThrownBy(() -> rated(Admission.CHARGE_NOW).build(kit(CALL_CADENCE)))
            .hasMessageContaining("a CHARGE_NOW lane holds no window, but the kit renews every 60 s — set the kit's reserve period to 0");
        assertThatThrownBy(() -> rated(Admission.CHARGE_NOW).window(Duration.ofSeconds(60)).build(kit(NO_RENEWAL)))
            .hasMessageContaining("a CHARGE_NOW lane holds no window, but the builder gives one");
        assertThat(rated(Admission.CHARGE_NOW).build(kit(NO_RENEWAL)).ownsItsRules()).isTrue();
    }

    @Test
    void theRulesAreSealed_onceBuilt() {
        Prepaid.Builder<Ctx> builder = rated(Admission.CHARGE_NOW);
        builder.build(kit(NO_RENEWAL));

        assertThatThrownBy(() -> builder.accounts(AccountRule.CASH_ONLY)).hasMessageContaining("[ad] the Prepaid is built: its rules are sealed");
    }

    @Test
    void aPrepaidServesTheKitItWasBuiltOn() {
        Prepaid<Ctx> onOne = rated(Admission.CHARGE_NOW).build(kit(NO_RENEWAL));
        RoutesphereFlow<Ctx> onAnother = flow(kit(NO_RENEWAL));

        assertThatThrownBy(() -> onOne.engineFor(onAnother.answers())).hasMessageContaining("the Prepaid 'ad' was built on another kit");
    }

    @Test
    void aLaneThatGivesNone_runsOnItsOwnAnswers() {
        RoutesphereKit kit = kit(NO_RENEWAL);
        Prepaid<Ctx> answers = Prepaid.ofTheLanesAnswers(kit);

        assertThat(answers.ownsItsRules()).isFalse();
        assertThat(answers.admission()).isNull();
        assertThat(answers.engineFor(flow(kit).answers())).isNotNull();
    }

    // ── the scene ───────────────────────────────────────────────────────────

    private static Prepaid.Builder<Ctx> rated(Admission admission) {
        return Prepaid.<Ctx>builder(admission == Admission.HOLD_WINDOW ? "call" : "ad").admission(admission)
            .rate((ctx, tier, account) -> BigDecimal.ONE).finalize(ctx -> Final.releaseAll());
    }

    private static RoutesphereKit kit(RoutesphereSettings settings) {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(1, "btcl-client", PartnerType.CUSTOMER);
        return RoutesphereKit.builder().tenants(TenantLookup.of(b.build())).ledger(new InMemoryLedger()).cdr(new RecordingCdrSink())
            .zone(ZoneId.of("Asia/Dhaka")).settings(settings).build();
    }

    private static RoutesphereFlow<Ctx> flow(RoutesphereKit kit) {
        return RoutesphereApp.<Ctx>builder("BuilderSupervisor")
            .serviceGroup(0).identifyRootLevelTenant((facts, find) -> "btcl").identifyPartner((ctx, k, find) -> find.byPartnerId(ctx, 1))
            .rateAtLevel((ctx, tier, partner) -> null).route(ctx -> RoutePlan.of(List.of())).delivered((ctx, o) -> false)
            .refundPolicy(ctx -> RefundPolicy.callsRule()).rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL).build(kit);
    }
}
