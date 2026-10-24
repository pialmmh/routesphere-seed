package com.telcobright.seed.sessionflow.base;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.DryRun;
import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.api.Lookups;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereApp;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.RouteEntry;
import com.telcobright.seed.sessionflow.api.SessionCause;
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
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-O item 2 · The route layer of {@code identifyPartner} (the owner's rule of 2026-10-25; the architect's A3–A6): the partner found
 * THROUGH its route — by the route's user name and password ({@code byRouteCredentials} / {@code bySystemId}: the hashed form recomputed,
 * the legacy plaintext compared in constant time), by the address it is served at ({@code byIp} with the port the lane saw, then the bare
 * ip — no {@code :5060} default; {@code byDomain}), by its SIP account, by the longest number prefix — each a get on the snapshot's index.
 * A refused credential is ONE cause, {@code CREDENTIALS_REJECTED}, for an unknown user name and a wrong password alike, distinct from
 * {@code PARTNER_NOT_FOUND}; the password is in no cause, no note, no text.
 */
class RouteLayerHelpersTest {

    static final String HASHED_PASSWORD = "s3cret-9", LEGACY_PASSWORD = "plain-pw";

    static Tenant btcl() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(9, "DirectCo", PartnerType.CUSTOMER)
            .routeCredentials(9, "DirectCo-api", "directco", HASHED_PASSWORD)
            .route(9, "10.0.0.9", "10.0.0.9:5080", "sip.directco.test", "10.0.0.8", "10.0.0.6:5060").sipAccount(9, "1001").prefix(9, "88019")
            .reseller(44, "Res44").partner(701, "Unilever", PartnerType.CUSTOMER)
            .legacyRouteCredentials(701, "Unilever-api", "unilever", LEGACY_PASSWORD)
            .route(701, "10.0.0.7:7023", "10.0.0.8:7023").prefix(701, "880171");
        return b.build();
    }

    private final RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(btcl()))
        .identification(List.of(new IdentificationRule("esl", "10.10.199.9:8021", "btcl")))
        .ledger(new InMemoryLedger()).cdr((id, tiers) -> { }).zone(ZoneId.of("Asia/Dhaka")).build();
    private final Lookups find = kit.lookups();

    private LabContext onBtcl() {
        LabContext ctx = new LabContext();
        ctx.tree = kit.tenants().snapshot("btcl").orElseThrow();
        return ctx;
    }

    private static String leaf(Tier t) { return t == null ? null : t.dbName() + "/" + t.partner().getIdPartner(); }

    @Test
    void the_route_credentials_name_the_partner_in_both_forms_and_a_refusal_is_one_cause() {
        assertThat(leaf(find.byRouteCredentials(onBtcl(), "directco", HASHED_PASSWORD))).as("the hashed form, recomputed").isEqualTo("btcl/9");
        assertThat(leaf(find.byRouteCredentials(onBtcl(), "unilever", LEGACY_PASSWORD))).as("the legacy plaintext").isEqualTo("res_44/701");
        assertThat(leaf(find.bySystemId(onBtcl(), "unilever", LEGACY_PASSWORD))).as("the SMPP name of the same check").isEqualTo("res_44/701");
        RouteEntry route = find.routeOfCredentials(onBtcl(), "unilever", LEGACY_PASSWORD);
        assertThat(route.routeName()).isEqualTo("Unilever-api");
        assertThat(route.toString()).doesNotContain(LEGACY_PASSWORD);

        LabContext wrong = onBtcl(), unknown = onBtcl(), missing = onBtcl(), hashAsPassword = onBtcl();
        assertThat(find.byRouteCredentials(wrong, "directco", "s3cret-8")).isNull();
        assertThat(find.byRouteCredentials(unknown, "nobody", HASHED_PASSWORD)).isNull();
        assertThat(find.byRouteCredentials(missing, "unilever", null)).isNull();
        String stored = find.routeOfCredentials(onBtcl(), "directco", HASHED_PASSWORD).metadata().getExtra2();
        assertThat(stored).as("the testkit stores the hashed form").startsWith("{pbkdf2}1000$");
        assertThat(find.byRouteCredentials(hashAsPassword, "directco", stored)).as("the stored hash is not the password").isNull();
        for (LabContext refused : List.of(wrong, unknown, missing, hashAsPassword)) {
            assertThat(refused.identificationCause).as("one cause for every refusal").isEqualTo(SessionCause.CREDENTIALS_REJECTED);
        }
        assertThat(onBtcl().identificationCause).isNull();
    }

    @Test
    void the_address_the_route_is_served_at_names_the_partner_with_the_port_first_and_no_default_port() {
        assertThat(leaf(find.byIp(onBtcl(), "10.0.0.9", 5080))).as("ip:port as served").isEqualTo("btcl/9");
        assertThat(leaf(find.byIp(onBtcl(), "10.0.0.9", 6000))).as("a port no route names: the bare ip").isEqualTo("btcl/9");
        assertThat(leaf(find.byIp(onBtcl(), "10.0.0.7", 7023))).as("a route served only at ip:port").isEqualTo("res_44/701");
        assertThat(find.byIp(onBtcl(), "10.0.0.7")).as("the bare ip: nothing served there").isNull();
        assertThat(find.byIp(onBtcl(), "10.0.0.7", 5060)).as("no :5060 default").isNull();
        assertThat(leaf(find.byIp(onBtcl(), "10.0.0.8", 7023))).as("ip:port first: another partner than the bare ip's").isEqualTo("res_44/701");
        assertThat(leaf(find.byIp(onBtcl(), "10.0.0.8", 5060))).as("then the bare ip").isEqualTo("btcl/9");
        assertThat(find.byIp(onBtcl(), "10.0.0.6")).as("a route served only at :5060 is not the bare ip's").isNull();
        assertThat(leaf(find.byIp(onBtcl(), "10.0.0.6", 5060))).as("the port the lane saw").isEqualTo("btcl/9");
        assertThat(leaf(find.byIp(onBtcl(), "10.0.0.9"))).isEqualTo("btcl/9");
        assertThat(leaf(find.byDomain(onBtcl(), "sip.directco.test"))).isEqualTo("btcl/9");
        assertThat(find.byIp(onBtcl(), null, 5060)).isNull();
        assertThat(onBtcl().identificationCause).isNull();
    }

    @Test
    void a_sip_account_a_prefix_and_the_partners_routes() {
        assertThat(leaf(find.bySipAccount(onBtcl(), "1001"))).isEqualTo("btcl/9");
        assertThat(find.bySipAccount(onBtcl(), "1002")).isNull();
        assertThat(leaf(find.byPrefix(onBtcl(), "8801711000002"))).isEqualTo("res_44/701");
        assertThat(leaf(find.byPrefix(onBtcl(), "8801911000002"))).isEqualTo("btcl/9");
        assertThat(find.byPrefix(onBtcl(), "8801811000002")).isNull();
        Tier directCo = find.bySipAccount(onBtcl(), "1001");
        assertThat(find.routesOf(directCo)).extracting(r -> r.getRouteName())
            .containsExactlyInAnyOrder("DirectCo-api", "10.0.0.9", "10.0.0.9:5080", "sip.directco.test", "10.0.0.8", "10.0.0.6:5060");
        assertThat(find.routesOf(null)).isEmpty();
    }

    // ── the cause through the admission ─────────────────────────────────────

    /** A candidate that names its caller: by its route credentials, or by a partner id. */
    record Caller(String username, String password, Integer partnerId) implements Candidate {}

    private RoutesphereFlow<LabContext> flowOf(List<Candidate> candidates) {
        BiFunction<LabContext, Caller, Tier> identify = (ctx, c) -> c.username() != null
            ? kit.lookups().byRouteCredentials(ctx, c.username(), c.password()) : kit.lookups().byPartnerId(ctx, c.partnerId());
        return RoutesphereApp.<LabContext>builder("LabSupervisor").serviceGroup(0)
            .identifyRootLevelTenant((facts, lookups) -> lookups.byIngress(facts.ingress()))
            .identifyPartner((ctx, candidate, lookups) -> identify.apply(ctx, (Caller) candidate))
            .selectCandidates(ctx -> candidates)
            .rateAtLevel((ctx, tier, partner) -> TierRate.of(BigDecimal.ONE, BigDecimal.ONE, "BDT", "880"))
            .route(ctx -> RoutePlan.one(new LabHop("carrier-a")))
            .delivered((ctx, outcome) -> ctx.answered())
            .refundPolicy(ctx -> RefundPolicy.callsRule())
            .rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL)
            .build(kit);
    }

    private DryRun dryRun(List<Candidate> candidates) {
        LabContext ctx = new LabContext();
        ctx.facts = new LabFacts("s-" + candidates.hashCode(), 1L, Lab.BTCL_DOOR, 0, "8801711000000", "btcl");
        DryRun dry = flowOf(candidates).simulate(ctx);
        assertThat(String.valueOf(ctx.history.snapshot())).as("no note carries a password").doesNotContain(HASHED_PASSWORD, "s3cret-8", LEGACY_PASSWORD);
        return dry;
    }

    @Test
    void the_admission_refuses_a_rejected_credential_with_its_own_cause_and_admits_a_good_one_through_the_tiers() {
        DryRun good = dryRun(List.of(new Caller("unilever", LEGACY_PASSWORD, null)));
        assertThat(good.admitted()).isTrue();
        assertThat(good.levels()).extracting(l -> l.getDbName() + "/" + l.getPartnerId()).containsExactly("res_44/701", "btcl/44");

        assertThat(dryRun(List.of(new Caller("directco", "s3cret-8", null))).cause()).isEqualTo(SessionCause.CREDENTIALS_REJECTED);
        assertThat(dryRun(List.of(new Caller("nobody", "x", null))).cause()).as("an unknown user name: the same cause").isEqualTo(SessionCause.CREDENTIALS_REJECTED);
        assertThat(dryRun(List.of(new Caller(null, null, 999))).cause()).as("an id nobody holds").isEqualTo(SessionCause.PARTNER_NOT_FOUND);
    }

    @Test
    void each_candidate_says_its_own_cause() {
        assertThat(dryRun(List.of(new Caller("directco", "s3cret-8", null), new Caller(null, null, 999))).cause())
            .as("the second candidate found nobody by its id: not the first's rejected credential").isEqualTo(SessionCause.PARTNER_NOT_FOUND);
        assertThat(dryRun(List.of(new Caller(null, null, 999), new Caller("directco", "s3cret-8", null))).cause())
            .isEqualTo(SessionCause.CREDENTIALS_REJECTED);
        assertThat(dryRun(List.of(new Caller("directco", "s3cret-8", null), new Caller("directco", HASHED_PASSWORD, null))).admitted())
            .as("a good credential after a refused one is admitted").isTrue();
    }
}
