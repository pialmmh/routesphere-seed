package com.telcobright.seed.prepaid.engine;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.seed.prepaid.dependencies.PrepaidEngineMaker;
import com.telcobright.seed.prepaid.internal.PrepaidEngine;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereApp;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.MoneyEngineMaker;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2a 2a (design §11): with seed-prepaid on the classpath, the base's kit finds ITS maker ({@code java.util.ServiceLoader}, the
 * registration in {@code META-INF/services}) and every flow's money engine is seed-prepaid's — one engine, made once per flow.
 */
class TheMakerOnTheClasspathTest {

    public static final class Ctx extends RoutesphereContext { public Ctx() {} }

    @Test
    void theKitFindsSeedPrepaidsMaker_andTheFlowsEngineIsThePrepaidEngine_madeOnce() {
        assertThat(MoneyEngineMaker.onTheClasspath()).isInstanceOf(PrepaidEngineMaker.class);

        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(1, "btcl-client", PartnerType.CUSTOMER);
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(b.build())).ledger(new InMemoryLedger()).cdr(new RecordingCdrSink())
            .zone(ZoneId.of("Asia/Dhaka")).build();
        RoutesphereFlow<Ctx> flow = RoutesphereApp.<Ctx>builder("MakerSupervisor")
            .serviceGroup(0).identifyRootLevelTenant((facts, find) -> "btcl").identifyPartner((ctx, k, find) -> find.byPartnerId(ctx, 1))
            .rateAtLevel((ctx, tier, partner) -> null).route(ctx -> RoutePlan.of(List.of())).delivered((ctx, o) -> false)
            .refundPolicy(ctx -> RefundPolicy.callsRule()).rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL).build(kit);

        assertThat(kit.moneyMaker()).isInstanceOf(PrepaidEngineMaker.class);
        assertThat(flow.money()).isInstanceOf(PrepaidEngine.class).isSameAs(flow.money());
    }
}
