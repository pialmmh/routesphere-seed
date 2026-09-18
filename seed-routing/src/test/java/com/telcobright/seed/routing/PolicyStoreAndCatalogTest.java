package com.telcobright.seed.routing;

import com.telcobright.seed.routing.admin.PolicyAdmin;
import com.telcobright.seed.routing.api.RequestRouter;
import com.telcobright.seed.routing.api.RoutingDecision;
import com.telcobright.seed.routing.api.RoutingRefusal;
import com.telcobright.seed.routing.api.RoutingRequest;
import com.telcobright.seed.routing.config.RequestRouters;
import com.telcobright.seed.routing.config.RoutingSettings;
import com.telcobright.seed.routing.policy.PolicyConflictException;
import com.telcobright.seed.routing.policy.PolicyFormatException;
import com.telcobright.seed.routing.policy.PolicyTypes;
import com.telcobright.seed.routing.policy.RoutingPolicy;
import com.telcobright.seed.routing.spi.PolicyStore;
import com.telcobright.seed.routing.spi.RouteDirectory;
import com.telcobright.seed.routing.store.InMemoryPolicyStore;
import com.telcobright.seed.routing.store.JdbcPolicyStore;
import com.telcobright.seed.routing.store.PolicyCatalog;
import com.telcobright.seed.routing.store.PolicySeeder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The entity in its store, the compiled snapshot the hot path reads, the config that picks the router, the admin calls. */
class PolicyStoreAndCatalogTest {
    private static final String BKASH_ONLY = """
        { "schema": 1, "rules": [ { "name": "btcl-wifi-retail", "priority": 100,
            "match": { "partner": "btcl", "app": "wifi-retail", "zone": "*" }, "routes": [ { "route": "bkash", "weight": 100 } ] } ],
          "default": { "reject": "no-rule-matched" } }""";
    private static final String BKASH_AND_NAGAD = BKASH_ONLY.replace("{ \"route\": \"bkash\", \"weight\": 100 }",
        "{ \"route\": \"bkash\", \"weight\": 70 }, { \"route\": \"nagad\", \"weight\": 30 }");

    private static JdbcPolicyStore h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return new JdbcPolicyStore(ds).ensureSchema().ensureSchema();      // twice: safe on every start
    }

    private static RoutingRequest wifi(String zone) {
        return RoutingRequest.of("payment").with("partner", "btcl").with("app", "wifi-retail").with("zone", zone).build();
    }

    @Test
    void bothStoresKeepTheSameRules() {
        for (PolicyStore store : List.of(new InMemoryPolicyStore(), h2())) {
            String before = store.fingerprint();
            RoutingPolicy v1 = store.save(RoutingPolicy.draft("payment", "BTCL-WiFi-Retail", "match-loadbalance", "first", BKASH_ONLY), "mustafa");
            assertEquals(1, v1.version());
            assertEquals("btcl-wifi-retail", v1.name(), "a name is an identifier: lower case");
            assertEquals("mustafa", v1.updatedBy());
            assertNotNull(v1.updatedAt());
            assertNotEquals(before, store.fingerprint(), "a save changes the fingerprint");

            RoutingPolicy v2 = store.save(v1.withDocument(BKASH_AND_NAGAD), "officer-2");
            assertEquals(2, v2.version());
            assertTrue(store.find("payment", "btcl-wifi-retail").orElseThrow().document().contains("nagad"));

            PolicyConflictException stale = assertThrows(PolicyConflictException.class, () -> store.save(v1.withDocument(BKASH_ONLY), "officer-3"),
                "an editor that started from version 1 must not overwrite version 2");
            assertEquals(2, stale.storedVersion());
            assertThrows(PolicyConflictException.class, () -> store.save(RoutingPolicy.draft("payment", "btcl-wifi-retail", "", null, BKASH_ONLY), "x"),
                "creating a name that exists is a conflict");
            assertThrows(PolicyFormatException.class, () -> store.save(RoutingPolicy.draft("payment", "has.a.dot", "", null, BKASH_ONLY), "x"));

            List<RoutingPolicy> history = store.history("payment", "btcl-wifi-retail", 10);
            assertEquals(List.of(2, 1), history.stream().map(RoutingPolicy::version).toList());
            assertEquals(1, store.list("payment").size());
            assertEquals(0, store.list("sms").size());
            assertEquals(1, store.list(null).size());

            assertTrue(store.delete("payment", "btcl-wifi-retail", "mustafa"));
            assertFalse(store.delete("payment", "btcl-wifi-retail", "mustafa"));
            assertTrue(store.find("payment", "btcl-wifi-retail").isEmpty());
        }
    }

    @Test
    void theCatalogServesTheNewVersionWithoutARestart_andKeepsTheLastGoodOneWhenADocumentIsBroken() {
        JdbcPolicyStore store = h2();
        PolicyTypes types = PolicyTypes.defaults();
        List<PolicyCatalog.Change> seen = new ArrayList<>();
        try (PolicyCatalog catalog = new PolicyCatalog(store, types).onChange(seen::add)) {
            RoutingPolicy v1 = store.save(RoutingPolicy.draft("payment", "p", "match-loadbalance", null, BKASH_ONLY), "t");
            catalog.start(0);
            RequestRouter router = RequestRouters.standard().create("payment", RoutingSettings.from(Map.of("payment.policy", "p")), "policy",
                new RequestRouters.Parts(catalog, RouteDirectory.ALL_UP, null));
            assertEquals("bkash", router.route(wifi("uttara")).pick().route());
            assertEquals(1, router.route(wifi("uttara")).policyVersion());
            assertFalse(catalog.reloadIfChanged(), "nothing changed: the poll reads nothing");

            RoutingPolicy v2 = store.save(v1.withDocument(BKASH_ONLY.replace("\"bkash\"", "\"bkash-2\"")), "t");
            assertTrue(catalog.reloadIfChanged());
            RoutingDecision d = router.route(wifi("uttara"));
            assertEquals("bkash-2", d.pick().route());
            assertEquals(2, d.policyVersion());

            store.save(v2.withDocument("{ \"rules\": [ { \"name\": \"broken\" } ] }"), "someone-with-sql");     // a hand edit past the admin's check
            assertTrue(catalog.reloadIfChanged());
            assertEquals("bkash-2", router.route(wifi("uttara")).pick().route(), "the last good version stays in service");
            assertEquals(1, catalog.problems().size());
            assertTrue(catalog.problems().get(0).error().contains("rules[0]"), catalog.problems().get(0).error());
            assertTrue(seen.stream().anyMatch(c -> c.what().equals("kept-last-good")), seen.toString());

            store.save(store.find("payment", "p").orElseThrow().withEnabled(false).withDocument(BKASH_ONLY), "t");
            catalog.reload();
            assertEquals(RoutingRefusal.POLICY_DISABLED, router.route(wifi("uttara")).refusal());
            assertTrue(catalog.problems().isEmpty());

            store.delete("payment", "p", "t");
            catalog.reload();
            assertEquals(RoutingRefusal.NO_POLICY, router.route(wifi("uttara")).refusal());
        }
    }

    @Test
    void theProfileDeclaresAPolicy_theSeederMakesItAnEntity_syncFollowsTheFile_ifAbsentLeavesTheStoreAlone() {
        Map<String, String> flat = new LinkedHashMap<>();
        flat.put("payment.policy", "btcl-wifi-retail");
        flat.put("store.kind", "jdbc");
        flat.put("seed.mode", "sync");
        flat.put("policies.btcl-wifi-retail.domain", "payment");
        flat.put("policies.btcl-wifi-retail.type", "match-loadbalance");
        flat.put("policies.btcl-wifi-retail.description", "BTCL public WiFi");
        flat.put("policies.btcl-wifi-retail.document", BKASH_ONLY);
        RoutingSettings settings = RoutingSettings.from(flat);
        assertEquals("btcl-wifi-retail", settings.domain("payment").policy());
        assertTrue(settings.store().jdbc());
        assertEquals(30, settings.store().reloadSeconds());
        assertEquals("", settings.domain("sms").policy(), "a domain the file does not mention routes by its default");

        JdbcPolicyStore store = h2();
        PolicyTypes types = PolicyTypes.defaults();
        assertEquals("created", PolicySeeder.seed(store, settings.declaredPolicies(), settings.seedMode(), types).get(0).what());
        assertEquals("same", PolicySeeder.seed(store, settings.declaredPolicies(), settings.seedMode(), types).get(0).what(), "a restart with the same file writes nothing");
        assertEquals(1, store.find("payment", "btcl-wifi-retail").orElseThrow().version());

        flat.put("policies.btcl-wifi-retail.document", BKASH_AND_NAGAD);
        RoutingSettings changed = RoutingSettings.from(flat);
        assertEquals("updated", PolicySeeder.seed(store, changed.declaredPolicies(), PolicySeeder.Mode.SYNC, types).get(0).what());
        assertEquals(2, store.find("payment", "btcl-wifi-retail").orElseThrow().version());
        assertEquals("config", store.find("payment", "btcl-wifi-retail").orElseThrow().updatedBy());

        assertEquals("kept", PolicySeeder.seed(store, settings.declaredPolicies(), PolicySeeder.Mode.IF_ABSENT, types).get(0).what(), "with a screen, the store is the truth");
        assertEquals(2, store.find("payment", "btcl-wifi-retail").orElseThrow().version());
        assertTrue(PolicySeeder.seed(store, settings.declaredPolicies(), PolicySeeder.Mode.OFF, types).isEmpty());

        flat.put("policies.btcl-wifi-retail.document", "{ \"rules\": [ { \"name\": \"x\", \"routes\": [] } ] }");
        PolicyFormatException wrong = assertThrows(PolicyFormatException.class,
            () -> PolicySeeder.seed(store, RoutingSettings.from(flat).declaredPolicies(), PolicySeeder.Mode.SYNC, types));
        assertTrue(wrong.getMessage().startsWith("routing.policies.btcl-wifi-retail: rules[0]"), wrong.getMessage());
        assertEquals(2, store.find("payment", "btcl-wifi-retail").orElseThrow().version(), "a wrong file never reaches the store");
    }

    @Test
    void configPicksTheRouter_aPolicyNameSwitchesPolicyRoutingOn_blankKeepsTheDomainsDefault() {
        PolicyCatalog catalog = new PolicyCatalog(new InMemoryPolicyStore(), PolicyTypes.defaults()).start(0);
        var dialplan = new com.telcobright.seed.routing.spi.DialplanSource() {
            @Override public List<Prefix> prefixesOf(RoutingRequest r) { return List.of(new Prefix("p1", "880", "")); }
            @Override public List<DialplanShare> dialplansOf(Prefix p) { return List.of(new DialplanShare("dp1", 100)); }
            @Override public List<RouteEntry> routesOf(String d) { return List.of(RouteEntry.of("igw", 1)); }
        };
        RequestRouters.Parts parts = new RequestRouters.Parts(catalog, RouteDirectory.ALL_UP, dialplan);
        RequestRouters routers = RequestRouters.standard();

        assertEquals("dialplan", routers.create("sms", RoutingSettings.from(Map.of()), RequestRouters.DIALPLAN, parts).type(), "call and SMS: dialplan by default");
        assertEquals("policy", routers.create("sms", RoutingSettings.from(Map.of("sms.policy", "bulk-sms-by-operator")), RequestRouters.DIALPLAN, parts).type());
        assertEquals("dialplan", routers.create("sms", RoutingSettings.from(Map.of("sms.policy", "kept-for-later", "sms.type", "dialplan")), RequestRouters.DIALPLAN, parts).type(),
            "an explicit type wins");
        assertEquals("igw", routers.create("call", RoutingSettings.from(Map.of()), RequestRouters.DIALPLAN, parts)
            .route(RoutingRequest.of("call").with("called", "8801711").build()).pick().route());

        IllegalStateException noName = assertThrows(IllegalStateException.class, () -> routers.create("payment", RoutingSettings.from(Map.of()), RequestRouters.POLICY, parts));
        assertTrue(noName.getMessage().contains("routing.payment.policy"), noName.getMessage());
        IllegalStateException noType = assertThrows(IllegalStateException.class, () -> routers.create("ad", RoutingSettings.from(Map.of("ad.type", "auction")), RequestRouters.POLICY, parts));
        assertTrue(noType.getMessage().contains("auction"), noType.getMessage());

        routers.register("auction", (domain, s, p) -> new RequestRouter() {
            @Override public String type() { return "auction"; }
            @Override public RoutingDecision route(RoutingRequest request, boolean trace) { return RoutingDecision.refused(RoutingRefusal.NO_RULE_MATCHED, "", "auction", null, 0, null, null); }
        });
        assertEquals("auction", routers.create("ad", RoutingSettings.from(Map.of("ad.type", "auction")), RequestRouters.POLICY, parts).type(), "a host adds a router type without touching the library");
        catalog.close();
    }

    @Test
    void theAdminCallsAreWhatAScreenWillNeed() {
        InMemoryPolicyStore store = new InMemoryPolicyStore();
        PolicyTypes types = PolicyTypes.defaults();
        PolicyCatalog catalog = new PolicyCatalog(store, types).start(0);
        RouteDirectory onlyBkash = new RouteDirectory() {
            @Override public boolean exists(String route) { return route.equals("bkash"); }
            @Override public boolean isUp(String route) { return route.equals("bkash"); }
        };
        PolicyAdmin admin = new PolicyAdmin(store, catalog, types, onlyBkash);

        PolicyAdmin.Checked bad = admin.check(RoutingPolicy.draft("payment", "p", "match-loadbalance", null, "{ \"rules\": [ { \"routes\": [ { \"weight\": 5 } ] } ] }"));
        assertFalse(bad.ok());
        assertTrue(bad.error().contains("rules[0].routes[0].route"), bad.error());

        PolicyAdmin.Checked odd = admin.check(RoutingPolicy.draft("payment", "p", "match-loadbalance", null, BKASH_AND_NAGAD));
        assertTrue(odd.ok());
        assertTrue(odd.warnings().get(0).contains("route 'nagad' is not a route of this switch"), odd.warnings().toString());

        PolicyAdmin.Saved saved = admin.save(RoutingPolicy.draft("payment", "p", "match-loadbalance", "first", BKASH_ONLY), "officer");
        assertEquals(1, saved.policy().version());
        assertTrue(saved.warnings().isEmpty());
        RoutingDecision d = admin.simulate("payment", "p", wifi("uttara"));
        assertEquals("bkash", d.pick().route(), "a save is in service on return");
        assertFalse(d.trace().isEmpty());

        RoutingDecision whatIf = admin.simulateDraft(saved.policy().withDocument(BKASH_AND_NAGAD), wifi("uttara"));
        assertEquals("bkash", whatIf.pick().route(), "nagad is unknown to the switch: skipped, said in the trace");
        assertTrue(String.join("\n", whatIf.trace()).contains("route nagad: unknown to this switch"), whatIf.trace().toString());
        assertEquals(1, admin.get("payment", "p").orElseThrow().version(), "a simulation saves nothing");

        assertEquals(2, admin.enable("payment", "p", false, "officer").orElseThrow().policy().version());
        assertEquals(RoutingRefusal.NO_POLICY, admin.simulate("payment", "nope", wifi("z")).refusal());
        assertEquals("match-loadbalance", admin.typeDescriptors().get(0).path("type").asText());
        assertThrows(PolicyFormatException.class, () -> admin.save(RoutingPolicy.draft("payment", "q", "match-loadbalance", null, "{}"), "officer"));
        assertTrue(admin.delete("payment", "p", "officer"));
        assertTrue(admin.inService().policies().isEmpty());
        catalog.close();
    }
}
