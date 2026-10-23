package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.seed.naming.api.Topics;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.api.TenantCreated;
import com.telcobright.seed.sessionflow.base.LabCall;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabFacts;
import com.telcobright.seed.sessionflow.base.LabFlow;
import com.telcobright.seed.sessionflow.base.LabSupervisor;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.internal.BootstrapRoad;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.session.events.SessionStart;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-E item 8 · a tenant created live (R10): prime-context provisions a root, says {@code tenant_created} (§C.2) on the platform topic, and
 * every running application that serves it loads it — its bootstrap again, its tree, its doors, its ledger, its CDR road, its topics — and
 * serves it with no restart; a new reseller tier reloads its root's tree. The same event twice is once; another application's is nothing.
 * The platform's Kafka is the fake's MockConsumer.
 */
class TenantCreatedLiveTest {

    private final BootstrapWorld w = new BootstrapWorld();
    private final Map<String, Object> sample = BootstrapWorld.small(MapBootstrapSource.read("/bootstrap/deployment.json"));
    private RoutesphereEngine<LabContext> engine;
    private RoutesphereKit kit;

    @AfterEach
    void stop() { if (engine != null) engine.close(); }

    /** The application up on btcl alone (prime-context has not provisioned link3 for it yet). */
    private void upOnBtclAlone() {
        w.source.serve(BootstrapWorld.servingOnly(sample, "btcl"));
        kit = RoutesphereKit.fromBootstrap(w.source, "ad-sphere", w.makers());
        engine = RoutesphereEngine.of(new LabSupervisor(new LabFlow(kit, Map.of(), List.of(), Set.of()))).signaling(LabCall.spec())
            .channel(new TestChannel<String, StatemachineEvent>("lab"), (id, frame) -> Delivery.to(LabCall.TYPE, frame)).start();
    }

    private BootstrapRoad road() { return kit.bootstrapRoad().orElseThrow(); }

    private static TenantCreated root(String root, String... apps) { return new TenantCreated(root, root, null, null, "root", List.of(apps), "2026-10-23T16:30:00Z"); }

    private String rootOfADialAt(String door) {
        LabContext ctx = new LabContext();
        ctx.facts = new LabFacts("d-" + door, 0, Ingress.listen("10.10.188.2", Integer.parseInt(door)), 1, "8801");
        engine.flow().onStart(ctx, new SessionStart(ctx.facts));
        return ctx.root;
    }

    @Test
    void theSampleMessage_isReadAsSectionC2Says() {
        TenantCreated e = TenantCreated.parse(new String(readSample(), java.nio.charset.StandardCharsets.UTF_8));
        assertThat(e.root()).isEqualTo("link3");
        assertThat(e.tierDb()).isEqualTo("link3_res_5");
        assertThat(e.parentDb()).isEqualTo("link3");
        assertThat(e.partnerId()).isEqualTo(5);
        assertThat(e.isRoot()).isFalse();
        assertThat(e.names("ad-sphere")).isTrue();
        assertThat(e.names("pay-sphere")).isFalse();
    }

    @Test
    void aNewRoot_onTheTopic_isServedLive_itsTreeDoorsLedgerCdrRoadAndTopics_noRestart() {
        upOnBtclAlone();
        assertThat(rootOfADialAt("7071")).as("link3's door is nobody's before").isNull();
        w.source.serve(sample);                                                              // prime-context provisioned link3
        MockConsumer<String, String> topic = w.kafka.consumers.get("10.10.188.2:9092");
        TopicPartition tp = new TopicPartition(Topics.TENANT_CREATED, 0);
        topic.rebalance(List.of(tp));
        topic.updateBeginningOffsets(Map.of(tp, 0L));
        topic.seek(tp, 0);
        topic.addRecord(new ConsumerRecord<>(Topics.TENANT_CREATED, 0, 0, "link3",
            "{\"root\": \"link3\", \"tierDb\": \"link3\", \"parentDb\": null, \"partnerId\": null, \"kind\": \"root\", \"apps\": [\"ad-sphere\"], \"at\": \"2026-10-23T16:30:00Z\"}"));
        await(() -> kit.deployment().orElseThrow().roots().contains("link3"));

        assertThat(rootOfADialAt("7071")).as("link3's door is link3's now").isEqualTo("link3");
        assertThat(w.trees.calls).contains("serve link3");
        assertThat(w.ledgerAsks).extracting(r -> r.root()).containsExactly("btcl", "link3");
        assertThat(w.kafka.topics.get("10.10.188.2:9092")).contains("cdr_link3", "config_event_loader_link3");
    }

    @Test
    void theSameEventTwice_isOnce() {
        upOnBtclAlone();
        w.source.serve(sample);
        road().onTenantCreated(root("link3", "ad-sphere"));
        road().onTenantCreated(root("link3", "ad-sphere"));
        assertThat(w.trees.calls).containsExactly("serve btcl", "serve link3");
        assertThat(w.ledgerAsks).extracting(r -> r.root()).containsExactly("btcl", "link3");
        assertThat(w.kafka.asks.get("10.10.188.2:9092")).as("btcl's topics, then link3's — once").hasSize(2);
    }

    @Test
    void aNewResellerTier_reloadsItsRootsTree_once() {
        upOnBtclAlone();
        TenantCreated tier = new TenantCreated("btcl", "btcl_res_9", "btcl", 9, "reseller", List.of("ad-sphere"), "2026-10-23T16:31:00Z");
        road().onTenantCreated(tier);
        road().onTenantCreated(tier);
        assertThat(w.trees.calls).containsExactly("serve btcl", "reload btcl");
        assertThat(kit.tenants().snapshot("btcl").orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void anotherApplicationsTenant_andATierOfARootNotServed_areNothing() {
        upOnBtclAlone();
        w.source.serve(sample);
        road().onTenantCreated(root("link3", "wifi-sphere"));
        road().onTenantCreated(new TenantCreated("ccl", "ccl_res_1", "ccl", 1, "reseller", List.of("ad-sphere"), "2026-10-23T16:32:00Z"));
        assertThat(w.trees.calls).containsExactly("serve btcl");
        assertThat(kit.deployment().orElseThrow().roots()).containsExactly("btcl");
    }

    @Test
    void aRootTheBootstrapDoesNotServeYet_isNotServed_andALaterWordIsHeard() {
        upOnBtclAlone();
        road().onTenantCreated(root("link3", "ad-sphere"));                                // the bootstrap still serves btcl alone
        assertThat(kit.deployment().orElseThrow().roots()).containsExactly("btcl");
        w.source.serve(sample);
        road().onTenantCreated(root("link3", "ad-sphere"));                                // the same word, now that prime-context serves it
        assertThat(kit.deployment().orElseThrow().roots()).containsExactly("btcl", "link3");
    }

    private static byte[] readSample() {
        try (var in = TenantCreatedLiveTest.class.getResourceAsStream("/bootstrap/tenant-created.json")) { return in.readAllBytes(); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    private static void await(BooleanSupplier condition) {
        long until = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        assertThat(condition.getAsBoolean()).as("within 5 s").isTrue();
    }
}
