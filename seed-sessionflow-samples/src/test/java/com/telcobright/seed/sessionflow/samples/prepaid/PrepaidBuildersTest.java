package com.telcobright.seed.sessionflow.samples.prepaid;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.seed.sessionflow.api.DryRun;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereApp;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.samples.sms.SmsContext;
import com.telcobright.seed.sessionflow.samples.voice.VoiceContext;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.session.SessionTimings;
import com.telcobright.statewalk.session.events.SessionStart;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2a · The samples' call-shaped and SMS-shaped Prepaid ({@link PrepaidBuilders}) on lanes written with RoutesphereApp: each builds on
 * its kit, and a dry run of one session walks its tiers on the Prepaid's rules — the account the rule chose, the unit's rate — moving no
 * money (the parity suite of seed-prepaid runs them on the real ledger).
 */
class PrepaidBuildersTest {

    record Facts(String sessionId, long atMs, Ingress ingress, int partnerId, String called) implements RoutesphereFacts {}

    record Hop(String to) implements com.telcobright.statewalk.session.Route {}

    static final LocalDateTime LATER = LocalDateTime.of(2030, 1, 1, 0, 0);

    @Test
    void theCallShapedPrepaid_holdsAMinuteOnThePackageAtHome_andCashAbroad() {
        RoutesphereKit kit = kit(new RoutesphereSettings(8, 2, 12_000, new SessionTimings(2, 2, 5, 10_800), 60, 58, 0, false, 200));
        RoutesphereFlow<VoiceContext> call = lane(RoutesphereApp.<VoiceContext>builder("CallSupervisor").prepaid(PrepaidBuilders.call(kit)), kit);

        DryRun home = call.simulate(session(new VoiceContext(), "c-1", "8801711000000"));
        DryRun abroad = call.simulate(session(new VoiceContext(), "c-2", "00442071234567"));

        assertTrue(home.admitted(), home.cause());
        assertEquals(11L, home.levels().get(0).getPackageAccountId(), "the package first");
        assertEquals(0, home.levels().get(0).getRate().compareTo(java.math.BigDecimal.ONE));
        assertEquals(12L, abroad.levels().get(0).getPackageAccountId(), "cash only abroad");
        assertEquals(0, abroad.levels().get(0).getRate().compareTo(PrepaidBuilders.CASH_PER_MINUTE));
    }

    @Test
    void theSmsShapedPrepaid_chargesPerMessageTimesSegments_onCashOrAMessageUnit() {
        RoutesphereKit kit = kit(RoutesphereSettings.defaults());
        RoutesphereFlow<SmsContext> sms = lane(RoutesphereApp.<SmsContext>builder("SmsSupervisor").prepaid(PrepaidBuilders.sms(kit, true)), kit);
        SmsContext three = session(new SmsContext(), "s-1", "8801711000000");
        three.segments = 3;

        DryRun run = sms.simulate(three);

        assertTrue(run.admitted(), run.cause());
        assertEquals(12L, run.levels().get(0).getPackageAccountId(), "talk minutes never pay a message: the cash");
        assertEquals(0, run.levels().get(0).getRate().compareTo(new java.math.BigDecimal("0.75")));
    }

    // ── the scene ───────────────────────────────────────────────────────────

    private static <C extends RoutesphereContext> RoutesphereFlow<C> lane(RoutesphereApp.Builder<C> app, RoutesphereKit kit) {
        return app.serviceGroup(0)
            .identifyRootLevelTenant((facts, find) -> "btcl")
            .identifyPartner((ctx, candidate, find) -> find.byPartnerId(ctx, ((Facts) ctx.facts).partnerId()))
            .buildTask(ctx -> { ctx.originatingCalledNumber = ((Facts) ctx.facts).called(); return null; })
            .route(ctx -> RoutePlan.of(List.of(new Hop("gw-1"))))
            .delivered((ctx, outcome) -> ctx.answered())
            .rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL)
            .build(kit);
    }

    private static <C extends RoutesphereContext> C session(C ctx, String id, String called) {
        ctx.facts = new Facts(id, 0, Ingress.esl("10.0.0.1", 8021), 5, called);
        return ctx;
    }

    private static RoutesphereKit kit(RoutesphereSettings settings) {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(5, "client", PartnerType.CUSTOMER).account(5, 11, 1, "TF_min", "100", LATER).account(5, 12, 2, "BDT", "100", LATER);
        return RoutesphereKit.builder().tenants(TenantLookup.of(b.build())).ledger(new InMemoryLedger()).cdr(new RecordingCdrSink())
            .zone(ZoneId.of("Asia/Dhaka")).settings(settings).build();
    }
}
