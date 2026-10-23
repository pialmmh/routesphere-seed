package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.internal.BootstrapRoad;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-E item 6 · the kit from ONE bootstrap url (R8): §C's sample envelope builds the whole kit — the served roots, their trees and doors,
 * each root's ledger, CDR road and journal, the pool and the timers — and the start names every resource it got. Refusals in words: no
 * roots, a root without its resources, a rule for a root not served, another app's url, a ledger kind no maker answers.
 */
class KitFromBootstrapTest {

    private final BootstrapWorld w = new BootstrapWorld();

    private RoutesphereKit kit() { return RoutesphereKit.fromBootstrap(w.source, "ad-sphere", w.makers()); }

    @Test
    void theSampleEnvelope_buildsTheWholeKit() {
        RoutesphereKit kit = kit();
        Deployment d = kit.deployment().orElseThrow();
        assertThat(d.tenant()).isEqualTo("platform");
        assertThat(d.app()).isEqualTo("ad-sphere");
        assertThat(d.env()).isEqualTo("bed");
        assertThat(d.roots()).containsExactly("btcl", "link3");
        assertThat(kit.settings().pool()).isEqualTo(2000);
        assertThat(kit.settings().threads()).isEqualTo(8);
        assertThat(kit.settings().timings().admittingSec()).isEqualTo(10);
        assertThat(kit.settings().timings().connectingSec()).isEqualTo(90);
        assertThat(kit.settings().timings().activeMaxSec()).isEqualTo(14400);
        assertThat(kit.zone()).isEqualTo(ZoneId.of("Asia/Dhaka"));

        assertThat(kit.resolver().tenantOf(Ingress.listen("10.10.188.2", 7070))).contains("btcl");
        assertThat(kit.resolver().tenantOf(Ingress.listen("10.10.188.2", 7071))).contains("link3");
        assertThat(kit.tenants().snapshot("link3")).isPresent();
        assertThat(w.ledgerAsks).extracting(r -> r.root() + "/" + r.switchDb().schema() + "/" + r.ledger().kind())
            .containsExactly("btcl/btcl/switch", "link3/link3/switch");
        assertThat(w.journalDirs).containsExactly(Path.of("/var/lib/ad-sphere/btcl"), Path.of("/var/lib/ad-sphere/link3"));
    }

    @Test
    void theStartNamesEveryResourceItGot() {
        BootstrapRoad road = kit().bootstrapRoad().orElseThrow();
        assertThat(road.describe())
            .contains("btcl: tree v0, doors [listen 10.10.188.2:7070 → btcl], ledger switch on jdbc:postgresql://10.10.188.2:5434/routesphere schema btcl,"
                + " CDR cdr_btcl on 10.10.188.2:9092, redis redis://10.10.188.2:6379, dir /var/lib/ad-sphere/btcl")
            .contains("link3: tree v0, doors [listen 10.10.188.2:7071 → link3], ledger switch on jdbc:postgresql://10.10.188.2:5434/routesphere schema link3,"
                + " CDR cdr_link3 on 10.10.188.2:9092, redis redis://10.10.188.2:6379, dir /var/lib/ad-sphere/link3");
    }

    @Test
    void eachRootsCdr_goesOnItsOwnRoad() {
        RoutesphereKit kit = kit();
        kit.cdr().publish("link3", "v-1", List.of(record("link3")));
        kit.cdr().publish("btcl", "v-2", List.of(record("btcl")));
        kit.cdr().publish("ccl", "v-3", List.of(record("ccl")));                 // a root not served: never another root's topic
        assertThat(w.kafka.producers.get("10.10.188.2:9092").history()).extracting(r -> r.topic() + ":" + r.key())
            .containsExactly("cdr_link3:v-1", "cdr_btcl:v-2");
    }

    @Test
    void aTiersMoney_goesToTheLedgerOfItsOwnRoot() {
        RoutesphereKit kit = kit();
        var link3Tier = kit.tenants().tenantByDbName("link3", "link3_res_5").orElseThrow();
        var level = new com.telcobright.rtc.domainmodel.LevelAdmission(0, link3Tier, link3Tier.getContext().getPartners().get(501), null);
        w.ledgers.get("link3").fund("link3_res_5", 501, "10.00");
        assertThat(kit.ledger().reserve(level, new java.math.BigDecimal("1.00"), "v-9#L0")).isPresent();
        assertThat(w.ledgers.get("link3").balanceOf("link3_res_5", 501)).isEqualByComparingTo("9.00");
        assertThat(w.ledgers.get("btcl").journal()).as("btcl's ledger never asked").isEmpty();
    }

    @Test
    void theTiersAreNamedByR9_theSeedRootsLegacyNamesByTheBootstrapsWord() {
        assertThat(kit().deployment().orElseThrow().tenantNames().tierDb("btcl", "btcl", 2)).as("the sample names no seed root").isEqualTo("btcl_res_2");
        w.source.serve(edited(rs -> rs.put("naming", Map.of("seed-root", "btcl"))));
        Deployment bed = kit().deployment().orElseThrow();
        assertThat(bed.tenantNames().tierDb("btcl", "btcl", 2)).isEqualTo("res_2");
        assertThat(bed.tenantNames().tierDb("link3", "link3", 5)).isEqualTo("link3_res_5");
    }

    // ── refusals ──

    @Test
    void anotherAppsUrl_isRefused() {
        assertThatThrownBy(() -> RoutesphereKit.fromBootstrap(w.source, "wifi-sphere", w.makers()))
            .hasMessageContaining("serves the app 'ad-sphere', not 'wifi-sphere'");
    }

    @Test
    void noRoots_isRefused() {
        w.source.serve(edited(rs -> rs.put("roots", List.of())));
        assertThatThrownBy(this::kit).hasMessageContaining("serves no root");
    }

    @Test
    void aRootWithoutItsResources_isRefused() {
        w.source.serve(edited(rs -> rs.put("roots", List.of("btcl", "link3", "ccl"))));
        assertThatThrownBy(this::kit).hasMessageContaining("the root 'ccl' is served").hasMessageContaining("config.routesphere.root.ccl is missing");
    }

    @Test
    void aRuleForARootNotServed_isRefused() {
        w.source.serve(edited(rs -> rs.put("identification", Map.of("rules", List.of(
            Map.of("root", "btcl", "kind", "listen", "match", "10.10.188.2:7070"), Map.of("root", "ccl", "kind", "listen", "match", "10.10.188.2:7072"))))));
        assertThatThrownBy(this::kit).hasMessageContaining("names the root 'ccl', which ad-sphere does not serve");
    }

    @Test
    void aRootWithoutItsKafka_isRefused() {
        w.source.serve(edited(rs -> rootBlock(rs, "link3").remove("kafka")));
        assertThatThrownBy(this::kit).hasMessageContaining("config.routesphere.root.link3.kafka.bootstrap is missing");
    }

    @Test
    void aLedgerKindNoMakerAnswers_isRefused() {
        w.source.serve(edited(rs -> rootBlock(rs, "link3").put("ledger", Map.of("kind", "abacus"))));
        assertThatThrownBy(this::kit).hasMessageContaining("the ledger kind 'abacus' has no maker on the classpath");
    }

    @Test
    void theLedgerKind_isTheSwitchWhenNotSaid() {
        w.source.serve(edited(rs -> rootBlock(rs, "link3").remove("ledger")));
        kit();
        assertThat(w.ledgerAsks).extracting(r -> r.root() + "/" + r.ledger().kind()).containsExactly("btcl/switch", "link3/switch");
    }

    // ── help ──

    private static CdrEvent record(String root) {
        CdrEvent cdr = new CdrEvent();
        cdr.tenant = root;
        cdr.resellerHierarchy = root;
        return cdr;
    }

    /** The sample, with its config.routesphere edited. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> edited(java.util.function.Consumer<Map<String, Object>> edit) {
        Map<String, Object> envelope = deepCopy(MapBootstrapSource.read("/bootstrap/deployment.json"));
        edit.accept((Map<String, Object>) ((Map<String, Object>) envelope.get("config")).get("routesphere"));
        return envelope;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rootBlock(Map<String, Object> rs, String root) { return (Map<String, Object>) ((Map<String, Object>) rs.get("root")).get(root); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> copy = new HashMap<>();
        m.forEach((k, v) -> copy.put(k, v instanceof Map<?, ?> inner ? deepCopy((Map<String, Object>) inner) : v));
        return copy;
    }
}
