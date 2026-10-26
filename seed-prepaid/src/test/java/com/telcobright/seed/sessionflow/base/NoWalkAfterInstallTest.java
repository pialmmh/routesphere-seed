package com.telcobright.seed.sessionflow.base;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.DryRun;
import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.api.Lookups;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereApp;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-O item 3 · No list on the hot path, proven at run time (the owner's strict rule of 2026-10-25): after the snapshot's install, every
 * map of every tier refuses to be walked — and every identification helper still answers, and a session still climbs three tiers by the
 * ids at the end of the tier names and a tier named by hand by its exact name. Before P1-O the climb looped the parent tier's partners at
 * every tier above the leaf ({@code partnerNamedAs}), and {@code byPartnerId} walked every tier on the first ask of each session.
 */
class NoWalkAfterInstallTest {

    static Tenant btcl() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        TenantTreeBuilder.TenantSpec root = b.root("btcl").partner(9, "DirectCo", PartnerType.CUSTOMER).partner(12, "res_acme", PartnerType.CUSTOMER)
            .route(9, "10.0.0.9", "sip.directco.test").sipAccount(9, "1001").prefix(9, "88019");
        root.reseller(44, "Res44").partner(701, "Unilever", PartnerType.CUSTOMER).legacyRouteCredentials(701, "Unilever-api", "unilever", "pw")
            .route(701, "10.0.0.7:7023").reseller(7, "Sub7").partner(801, "Walton", PartnerType.CUSTOMER).prefix(801, "880171");
        b.tenant("res_acme", "btcl").partner(901, "Shop", PartnerType.CUSTOMER);
        return b.build();
    }

    private final ArmedTree armed = ArmedTree.of(btcl());
    private final TenantLookup trees = TenantLookup.of(armed.root());            // the install walks every tier — unarmed
    private final RoutesphereKit kit = RoutesphereKit.builder().tenants(trees)
        .identification(List.of(new IdentificationRule("esl", "10.10.199.9:8021", "btcl")))
        .ledger(new InMemoryLedger()).cdr((id, tiers) -> { }).zone(ZoneId.of("Asia/Dhaka")).build();
    private final Lookups find = kit.lookups();
    private final RoutesphereFlow<LabContext> flow = RoutesphereApp.<LabContext>builder("LabSupervisor").serviceGroup(0)
        .identifyRootLevelTenant((facts, lookups) -> lookups.byIngress(facts.ingress()))
        .identifyPartner((ctx, candidate, lookups) -> lookups.byPartnerId(ctx, ((LabFacts) ctx.facts).partnerId()))
        .rateAtLevel((ctx, tier, partner) -> TierRate.of(BigDecimal.ONE, BigDecimal.ONE, "BDT", "880"))
        .route(ctx -> RoutePlan.one(new LabHop("carrier-a")))
        .delivered((ctx, outcome) -> ctx.answered())
        .refundPolicy(ctx -> RefundPolicy.callsRule())
        .rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL)
        .build(kit);

    private LabContext session(int payer) {
        LabContext ctx = new LabContext();
        ctx.facts = new LabFacts("s-" + payer, 1L, Lab.BTCL_DOOR, payer, "8801711000000", "btcl");
        ctx.tree = trees.snapshot("btcl").orElseThrow();
        return ctx;
    }

    private static String leaf(Tier t) { return t == null ? null : t.dbName() + "/" + t.partner().getIdPartner(); }

    @Test
    void after_the_install_every_helper_answers_with_gets_only() {
        armed.arm();
        LabContext ctx = session(0);
        assertThat(leaf(find.byPartnerId(ctx, 801))).isEqualTo("res_44_7/801");
        assertThat(leaf(find.byPartnerName(ctx, "Unilever"))).isEqualTo("res_44/701");
        assertThat(leaf(find.byRouteCredentials(ctx, "unilever", "pw"))).isEqualTo("res_44/701");
        assertThat(leaf(find.byIp(ctx, "10.0.0.7", 7023))).isEqualTo("res_44/701");
        assertThat(leaf(find.byIp(ctx, "10.0.0.9", 5060))).isEqualTo("btcl/9");
        assertThat(leaf(find.byDomain(ctx, "sip.directco.test"))).isEqualTo("btcl/9");
        assertThat(leaf(find.bySipAccount(ctx, "1001"))).isEqualTo("btcl/9");
        assertThat(leaf(find.byPrefix(ctx, "8801711000002"))).isEqualTo("res_44_7/801");
        assertThat(find.routesOf(find.byPartnerId(ctx, 701))).hasSize(2);
    }

    @Test
    void after_the_install_a_session_climbs_by_the_ids_and_by_an_exact_name_without_walking_a_tier() {
        armed.arm();
        DryRun three = flow.simulate(session(801));
        assertThat(three.admitted()).as(String.valueOf(three.cause())).isTrue();
        assertThat(three.levels()).extracting(l -> l.getDbName() + "/" + l.getPartnerId()).containsExactly("res_44_7/801", "res_44/7", "btcl/44");

        DryRun byHand = flow.simulate(session(901));
        assertThat(byHand.admitted()).as(String.valueOf(byHand.cause())).isTrue();
        assertThat(byHand.levels()).extracting(l -> l.getDbName() + "/" + l.getPartnerId())
            .as("res_acme names no id: the root's partner named exactly res_acme").containsExactly("res_acme/901", "btcl/12");
    }
}
