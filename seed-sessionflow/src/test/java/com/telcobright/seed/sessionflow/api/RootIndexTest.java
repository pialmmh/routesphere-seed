package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.Route;
import com.telcobright.rtc.domainmodel.nonentity.DynamicContext;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-O item 1 · ONE root tree's index (the architect's A2): every tier walked once into flat maps — partner id, exact partner name, route key
 * (ip · ip:port · domain, as served), route user name, SIP account, prefix by length — each answer the tier and the SAME partner instance
 * its tier's {@code partners} holds. A key two holders share names nobody (D5 for every key); a route whose tier does not hold its partner
 * is left out.
 */
class RootIndexTest {

    static Tenant btcl() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl")
            .partner(9, "DirectCo", PartnerType.CUSTOMER).partner(44, "Res44", PartnerType.RESELLER).partner(2, "Root Two", PartnerType.CUSTOMER)
            .route(9, "10.0.0.9", "10.0.0.9:5080", "sip.directco.test").route(9, "10.0.0.66")
            .sipAccount(9, "1001").prefix(9, "8801").prefix(9, "88017")
            .legacyRouteCredentials(9, "DirectCo-api", "directco-api", "s3cret").legacyRouteCredentials(9, "Twin-api", "twin-api", "a");
        b.tenant("res_44", "btcl")
            .partner(701, "Unilever", PartnerType.CUSTOMER).partner(2, "Deep Two", PartnerType.CUSTOMER).partner(703, "Twin", PartnerType.CUSTOMER)
            .partner(704, "Twin", PartnerType.CUSTOMER).partner(705, "unilever", PartnerType.CUSTOMER)
            .route(701, "10.0.0.7").route(701, "10.0.0.66")
            .sipAccount(701, "2001").prefix(701, "880171")
            .legacyRouteCredentials(701, "Unilever-api", "unilever-api", "pw").legacyRouteCredentials(701, "Twin-api-2", "twin-api", "b");
        return b.build();
    }

    private final Tenant tree = btcl();
    private final RootIndex index = new RootTreeInstaller().install("btcl", 1, tree).index();

    private String tierAndPartner(EntryPartner e) { return e == null ? null : e.tenant().getDbName() + "/" + e.partner().getIdPartner(); }

    @Test
    void every_key_answers_its_tier_and_the_partner_its_tier_holds() {
        assertThat(tierAndPartner(index.partner(701))).isEqualTo("res_44/701");
        assertThat(tierAndPartner(index.partner(9))).isEqualTo("btcl/9");
        assertThat(tierAndPartner(index.routeKey("10.0.0.9"))).isEqualTo("btcl/9");
        assertThat(tierAndPartner(index.routeKey("10.0.0.9:5080"))).as("an ip:port key, as served").isEqualTo("btcl/9");
        assertThat(tierAndPartner(index.routeKey("sip.directco.test"))).as("a domain").isEqualTo("btcl/9");
        assertThat(tierAndPartner(index.routeKey("10.0.0.7"))).isEqualTo("res_44/701");
        assertThat(tierAndPartner(index.sipAccount("2001"))).isEqualTo("res_44/701");
        assertThat(tierAndPartner(index.routeOfUsername("unilever-api").entry())).isEqualTo("res_44/701");
        assertThat(index.routeOfUsername("unilever-api").routeName()).isEqualTo("Unilever-api");
        Tenant res44 = tree.findTenantByDbName("res_44");
        assertThat(index.routeKey("10.0.0.7").partner()).as("the tier's own partner instance").isSameAs(res44.getContext().getPartners().get(701));
        assertThat(index.routeOfUsername("unilever-api").partner()).isSameAs(res44.getContext().getPartners().get(701));
        assertThat(index.routeKey("10.0.0.10")).isNull();
        assertThat(index.routeOfUsername("nobody")).isNull();
        assertThat(index.routeKey(null)).isNull();
    }

    @Test
    void a_name_is_exact_and_a_name_two_partners_share_names_nobody() {
        assertThat(tierAndPartner(index.partnerNamed("Unilever"))).isEqualTo("res_44/701");
        assertThat(tierAndPartner(index.partnerNamed("unilever"))).as("exact: another partner's name").isEqualTo("res_44/705");
        assertThat(index.partnerNamed("UNILEVER")).as("no case-blind fallback").isNull();
        assertThat(index.partnerNamed("Twin")).as("two partners of res_44 share it").isNull();
        assertThat(index.partnerNamedIn(res44(), "Unilever").getIdPartner()).isEqualTo(701);
        assertThat(index.partnerNamedIn(tree, "Unilever")).as("not a partner of the root").isNull();
    }

    @Test
    void the_longest_prefix_answers_with_one_get_per_length_present() {
        assertThat(tierAndPartner(index.longestPrefixOf("8801711000002"))).isEqualTo("res_44/701");
        assertThat(tierAndPartner(index.longestPrefixOf("8801811000002"))).isEqualTo("btcl/9");
        assertThat(tierAndPartner(index.longestPrefixOf("88017"))).isEqualTo("btcl/9");
        assertThat(index.longestPrefixOf("880")).as("shorter than every prefix").isNull();
        assertThat(index.longestPrefixOf("4401711")).isNull();
        assertThat(index.prefixLengths()).as("4, 5 and 6 digits: at most three gets").isEqualTo(3);
    }

    @Test
    void a_key_two_holders_share_names_nobody_and_is_listed() {
        assertThat(index.partner(2)).as("partner 2 is in btcl and in res_44").isNull();
        assertThat(index.routeKey("10.0.0.66")).as("a route key of two tiers").isNull();
        assertThat(index.routeOfUsername("twin-api")).as("a user name of two tiers").isNull();
        assertThat(index.heldByTwo()).contains("partner-id 2", "route-key 10.0.0.66", "username twin-api", "partner-name Twin");
        assertThat(index.heldByTwo()).doesNotContain("route-key 10.0.0.9", "partner-id 9");
    }

    @Test
    void a_route_whose_tier_does_not_hold_its_partner_is_left_out() {
        Route orphan = new Route();
        orphan.setIdroute(1);
        orphan.setRouteName("orphan");
        orphan.setIdPartner(9);                                                   // the tier holds no partner 9
        orphan.setField5("10.0.0.19");
        DynamicContext context = new DynamicContext(Map.of(), Map.of(), Map.of("10.0.0.19", orphan), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            List.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        Tenant ccl = new Tenant("ccl");
        ccl.setName("ccl");
        ccl.setContext(context);
        ccl.rebuildTenantIndex();
        ccl.computeAncestorChains();
        RootIndex left = new RootTreeInstaller().install("ccl", 1, ccl).index();
        assertThat(left.routeKey("10.0.0.19")).as("left out: no partner to name").isNull();
        assertThat(left.heldByTwo()).isEmpty();
    }

    @Test
    void the_lookup_of_given_trees_installs_each_once_and_a_lookup_that_installs_nothing_gets_each_tree_object_once() {
        TenantLookup served = TenantLookup.of(btcl(), new TenantTreeBuilder().root("ccl").partner(9, "CCL", PartnerType.CUSTOMER).and().build());
        assertThat(served.rootIndexBuilds()).as("two roots installed at the construction").isEqualTo(2);
        RootTree btcl = served.snapshot("btcl").orElseThrow();
        for (int session = 0; session < 1_000; session++) assertThat(served.snapshot("btcl")).containsSame(btcl);
        assertThat(served.rootIndexBuilds()).isEqualTo(2);

        Tenant own = btcl();
        TenantLookup lanesOwn = new TenantLookup() {                              // a lane's lookup with no install step of its own
            @Override public Optional<Tenant> root(String rootDbName) { return "btcl".equals(rootDbName) ? Optional.of(own) : Optional.empty(); }
            @Override public Optional<Tenant> tenantOfPartner(String rootDbName, int partnerId) { return Optional.empty(); }
            @Override public Optional<Tenant> tenantByDbName(String rootDbName, String dbName) { return Optional.empty(); }
            @Override public Optional<Tenant> rootOfCode(String code) { return Optional.empty(); }
        };
        RootTree once = lanesOwn.snapshot("btcl").orElseThrow();
        for (int session = 0; session < 1_000; session++) assertThat(lanesOwn.snapshot("btcl")).containsSame(once);
        assertThat(lanesOwn.rootIndexBuilds()).isEqualTo(TenantLookup.NOT_COUNTED);
    }

    private Tenant res44() { return tree.findTenantByDbName("res_44"); }
}
