package com.telcobright.seed.sessionflow;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereApp;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.RoutesphereSupervisor;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.MoneyEngine;
import com.telcobright.seed.sessionflow.spi.MoneyEngineMaker;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.registry.MachineSpec;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.Reject;
import com.telcobright.statewalk.session.events.SessionStart;
import com.telcobright.statewalk.session.events.SignalingFailed;
import com.telcobright.statewalk.session.events.Stop;
import com.telcobright.statewalk.state.StateMap;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2a (design {@code prepaid-engine-design.md} §11): the base never moves money itself — every money step is ONE call into the lane's
 * money engine, which the kit's maker makes (seed-prepaid's, found on the classpath). This module's classpath carries NO maker: a flow's
 * engine is refused in words, and so is an engine's start; a kit given a maker of its own asks it, once per flow.
 */
class NoMoneyEngineOnTheClasspathTest {

    record Facts(String sessionId, long atMs, Ingress ingress) implements RoutesphereFacts {}

    public static final class Ctx extends RoutesphereContext { public Ctx() {} }

    record Go(Facts facts) implements StatemachineEvent { @Override public boolean isFirst() { return true; } }

    static final class Sup extends RoutesphereSupervisor<Ctx> { Sup(RoutesphereFlow<Ctx> flow) { super(flow, Ctx::new); } }

    @Test
    void theBaseAloneCarriesNoMaker() {
        assertThat(MoneyEngineMaker.onTheClasspath()).isSameAs(MoneyEngineMaker.NONE);
        assertThat(kit(null).moneyMaker()).isSameAs(MoneyEngineMaker.NONE);
    }

    @Test
    void aFlowWithoutAnEngine_isRefusedInWords() {
        RoutesphereFlow<Ctx> flow = flow(kit(null));

        assertThatThrownBy(flow::money).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("[NoMoneySupervisor] no money engine on the classpath")
            .hasMessageContaining("seed-prepaid is not on the classpath")
            .hasMessageContaining("the base never moves money itself");
    }

    @Test
    void theEnginesStart_isRefusedInWords() {
        RoutesphereFlow<Ctx> flow = flow(kit(null));
        TestChannel<String, StatemachineEvent> wire = new TestChannel<>("no-money");

        assertThatThrownBy(() -> RoutesphereEngine.of(new Sup(flow)).signaling(wire()).channel(wire, (id, e) -> Delivery.to("NoMoneyWire", e)).start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("seed-prepaid is not on the classpath");
    }

    @Test
    void aKitGivenAMakerOfItsOwn_asksIt_oncePerFlow() {
        AtomicInteger made = new AtomicInteger();
        MoneyEngine<Ctx> engine = new NothingMoves();
        RoutesphereFlow<Ctx> flow = flow(kit(new MoneyEngineMaker() {
            @Override
            @SuppressWarnings("unchecked")
            public <C extends RoutesphereContext> MoneyEngine<C> make(RoutesphereFlow<C> f) {
                made.incrementAndGet();
                return (MoneyEngine<C>) engine;
            }
        }));

        assertThat(flow.money()).isSameAs(engine);
        assertThat(flow.money()).isSameAs(engine);
        assertThat(made).hasValue(1);
    }

    // ── the scene ───────────────────────────────────────────────────────────

    private static RoutesphereKit kit(MoneyEngineMaker maker) {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(1, "btcl-client", PartnerType.CUSTOMER);
        RoutesphereKit.Builder kit = RoutesphereKit.builder().tenants(TenantLookup.of(b.build())).ledger(new InMemoryLedger())
            .cdr(new RecordingCdrSink()).zone(ZoneId.of("Asia/Dhaka"));
        if (maker != null) kit.money(maker);
        return kit.build();
    }

    private static RoutesphereFlow<Ctx> flow(RoutesphereKit kit) {
        return RoutesphereApp.<Ctx>builder("NoMoneySupervisor")
            .serviceGroup(0)
            .identifyRootLevelTenant((facts, find) -> "btcl")
            .identifyPartner((ctx, candidate, find) -> find.byPartnerId(ctx, 1))
            .rateAtLevel((ctx, tier, partner) -> null)
            .route(ctx -> RoutePlan.of(List.of()))
            .delivered((ctx, outcome) -> false)
            .refundPolicy(ctx -> RefundPolicy.callsRule())
            .rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL)
            .build(kit);
    }

    /** The smallest signaling machine the engine's start reads: it shows its SessionStart sample (R2). */
    private static MachineSpec<Object> wire() {
        return MachineSpec.<Object>builder()
            .name("NoMoneyWire").contextFactory(Object::new)
            .upSample(new SessionStart(new Facts("sample", 0, Ingress.esl("10.10.199.9", 8021))))
            .stateMap(StateMap.builder()
                .initialState(StateMap.IDLE)
                .state(StateMap.IDLE).timeout(0)
                    .on(Go.class, "INIT", null, null, (self, e) -> new SessionStart(((Go) e).facts()))
                .state("INIT").interim().timeout(0)
                    .on(Reject.class, "DONE", null, null, (self, e) -> new SignalingFailed(0, ((Reject) e).cause(), 403))
                    .on(Stop.class, "DONE", null, null, (self, e) -> new SignalingFailed(0, ((Stop) e).cause(), 403))
                .state("DONE").finalState().timeout(1, SECONDS, "DONE")
                .build())
            .build();
    }

    /** An engine that moves nothing (the kit's own maker answers it). */
    private static final class NothingMoves implements MoneyEngine<Ctx> {
        @Override public Verdict planReserve(Ctx ctx, Tier tier, Partner partner) { return Verdict.ok(); }
        @Override public void release(Ctx ctx, List<LevelAdmission> held, String why) { }
        @Override public double reserveNextWindow(Ctx ctx) { return 0; }
        @Override public double renewTier(Ctx ctx, LevelAdmission tier) { return 0; }
        @Override public String hold(Ctx ctx, LevelAdmission tier, BigDecimal amount, String reference) { return null; }
        @Override public void finalizeBalance(Ctx ctx, Outcome outcome, boolean answered) { }
        @Override public TierSettlement settleTier(Ctx ctx, LevelAdmission tier, Outcome outcome) { return TierSettlement.nothing(tier.getLevelIndex()); }
        @Override public int[] closeAsReserved(SessionJournal.Leftover left) { return new int[] {0, 0}; }
        @Override public boolean giveBackLeftover(String sessionId, SessionJournal.Held held) { return true; }
    }
}
