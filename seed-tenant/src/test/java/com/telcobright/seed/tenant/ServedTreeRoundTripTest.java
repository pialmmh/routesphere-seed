package com.telcobright.seed.tenant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.mysqlentity.Route;
import com.telcobright.rtc.domainmodel.mysqlentity.RouteMetadata;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.Lookups;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.api.RootTreeInstaller;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.dependencies.TenantResolvers;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.tenant.internal.Trees;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-O item 1b (prime-context's PC-0025 finding) · The index on a tree AS PRIME-CONTEXT SERVES IT, not as the testkit holds it in memory:
 * on the wire a {@code usernameVsRouteMetaData} row carries NO route (prime-context's {@code RouteMetadata.route} is {@code @JsonIgnore}),
 * the tree's index and chains are not there, and a route's {@code partner} may or may not be (a copy when it is). A testkit tree is
 * serialized that way, read back by {@code Trees.parse}, installed, and the credentials, the address and the SIP account still name the right
 * tier and the tier's OWN partner instance. With {@code partnerVsRoutes} on the wire rtc-domain's deserializer wires a row's route itself;
 * with only {@code idVsRoute} it cannot — the index finds the row's route by the row's id among the tier's routes (A2), or skips it with
 * one WARN.
 */
class ServedTreeRoundTripTest {

    static final String SECRET = "Zx9_-k3Qm8vR2tY7uL0pN4wB6cE1hJ5gF8dS3aK7lM2";

    /** prime-context's wire: a row's route is {@code @JsonIgnore} there. */
    abstract static class RowWithoutItsRoute { @JsonIgnore abstract Route getRoute(); }

    /** A route's {@code @Transient} partner, left off the wire. */
    abstract static class RouteWithoutItsPartner { @JsonIgnore abstract Partner getPartner(); }

    /** The flat index and the ancestor chains are not on the wire (Trees.ready rebuilds them). */
    abstract static class TenantWithoutItsIndex {
        @JsonIgnore abstract Map<String, Tenant> getTenantIndex();
        @JsonIgnore abstract List<Tenant> getAncestorChain();
    }

    static Tenant btcl() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(9, "DirectCo", PartnerType.CUSTOMER).routeCredentials(9, "DirectCo-api", "directco", SECRET)
                .route(9, "10.0.0.9").sipAccount(9, "1001")
            .reseller(44, "Res44").partner(701, "Unilever", PartnerType.CUSTOMER).legacyRouteCredentials(701, "Unilever-api", "unilever", "plain-pw")
                .route(701, "10.0.0.7:7023").sipAccount(701, "2001");
        return b.build();
    }

    /** The tree as prime-context's JSON — the route's partner on the wire or not. */
    static String served(boolean routePartnerOnTheWire) throws IOException {
        ObjectMapper wire = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .addMixIn(RouteMetadata.class, RowWithoutItsRoute.class).addMixIn(Tenant.class, TenantWithoutItsIndex.class);
        if (!routePartnerOnTheWire) wire.addMixIn(Route.class, RouteWithoutItsPartner.class);
        return wire.writeValueAsString(btcl());
    }

    /** The same JSON with {@code partnerVsRoutes} dropped from every tier: the routes only in {@code idVsRoute} (and the address map). */
    static String withRoutesOnlyByTheirIds(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        dropPartnerVsRoutes(root);
        return mapper.writeValueAsString(root);
    }

    private static void dropPartnerVsRoutes(JsonNode tenant) {
        if (tenant.get("context") instanceof ObjectNode context) context.remove("partnerVsRoutes");
        JsonNode children = tenant.get("children");
        if (children != null) children.forEach(ServedTreeRoundTripTest::dropPartnerVsRoutes);
    }

    private static RoutesphereContext sessionOn(RootTree tree) {
        RoutesphereContext ctx = new RoutesphereContext();
        ctx.tree = tree;
        return ctx;
    }

    private static String leaf(Tier t) { return t == null ? null : t.dbName() + "/" + t.partner().getIdPartner(); }

    private final Lookups find = new Lookups(TenantResolvers::none);

    @Test
    void the_wire_carries_no_route_on_a_user_names_row() throws IOException {
        JsonNode row = new ObjectMapper().readTree(served(false)).path("context").path("usernameVsRouteMetaData").path("directco");
        assertThat(row.path("extra1").asText()).isEqualTo("directco");
        assertThat(row.has("route")).as("prime-context's RouteMetadata.route is @JsonIgnore").isFalse();
    }

    @Test
    void on_the_served_tree_the_credentials_the_address_and_the_sip_account_name_the_tier_and_its_own_partner() throws IOException {
        for (String wire : List.of(served(false), served(true), withRoutesOnlyByTheirIds(served(false)), withRoutesOnlyByTheirIds(served(true)))) {
            RootTree tree = new RootTreeInstaller().install("btcl", 1, Trees.parse(wire));
            RoutesphereContext ctx = sessionOn(tree);
            Partner directCo = tree.tree().getContext().getPartners().get(9);
            Partner unilever = tree.tree().findTenantByDbName("res_44").getContext().getPartners().get(701);

            assertThat(leaf(find.byRouteCredentials(ctx, "directco", SECRET))).isEqualTo("btcl/9");
            assertThat(find.byRouteCredentials(ctx, "directco", SECRET).partner()).as("the tier's own instance, never route.partner").isSameAs(directCo);
            assertThat(leaf(find.byRouteCredentials(ctx, "unilever", "plain-pw"))).isEqualTo("res_44/701");
            assertThat(find.routeOfCredentials(ctx, "unilever", "plain-pw").routeName()).isEqualTo("Unilever-api");
            assertThat(leaf(find.byIp(ctx, "10.0.0.7", 7023))).isEqualTo("res_44/701");
            assertThat(find.byIp(ctx, "10.0.0.7", 7023).partner()).isSameAs(unilever);
            assertThat(leaf(find.byIp(ctx, "10.0.0.9"))).isEqualTo("btcl/9");
            assertThat(leaf(find.bySipAccount(ctx, "2001"))).isEqualTo("res_44/701");
            assertThat(find.bySipAccount(ctx, "1001").partner()).isSameAs(directCo);
            assertThat(find.byRouteCredentials(ctx, "directco", "wrong")).isNull();
            assertThat(tree.index().heldByTwo()).isEmpty();
        }
    }

    @Test
    void with_the_routes_only_by_their_ids_the_parsed_row_has_no_route_and_the_index_finds_it_by_the_rows_id() throws IOException {
        Tenant parsed = Trees.parse(withRoutesOnlyByTheirIds(served(false)));
        assertThat(parsed.getContext().getUsernameVsRouteMetaData().get("directco").getRoute())
            .as("rtc-domain's deserializer wires a row's route from partnerVsRoutes only: here it cannot").isNull();
        assertThat(parsed.getContext().getPartnerVsRoutes()).isEmpty();
        RootTree tree = new RootTreeInstaller().install("btcl", 1, parsed);
        assertThat(tree.index().routeOfUsername("directco").route().getRouteName()).isEqualTo("DirectCo-api");
        assertThat(tree.index().routeOfUsername("directco").partner()).isSameAs(parsed.getContext().getPartners().get(9));
    }
}
