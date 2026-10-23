package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.base.Lab;
import com.telcobright.seed.sessionflow.base.LabCall;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabEvents.Answer;
import com.telcobright.seed.sessionflow.base.LabEvents.Dial;
import com.telcobright.seed.sessionflow.base.LabEvents.Hangup;
import com.telcobright.seed.sessionflow.base.LabFacts;
import com.telcobright.seed.sessionflow.base.LabFlow;
import com.telcobright.seed.sessionflow.base.LabSupervisor;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.SdrSink;
import com.telcobright.seed.sessionflow.spi.TaskSink;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.ManualClock;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-F item 2 · a kit made from the bootstrap takes the lane's OWN sinks before it is used ({@code toBuilder()}): the ad's SDR writer and
 * its task store beside everything the road made — the trees, the doors, the ledger, the CDR road, the journal, the clock, the zone, the
 * settings and the road itself, so the engine's close still closes what the road opened. A kit an engine already runs on is not copied.
 */
class LaneSinksOnTheBootstrapTest {

    private static final String BTCL_KAFKA = "10.10.188.2:9092";

    private final BootstrapWorld w = new BootstrapWorld();
    private RoutesphereEngine<LabContext> engine;

    @AfterEach
    void stop() { if (engine != null) engine.close(); }

    private RoutesphereKit fromTheBootstrap() {
        w.source.serve(BootstrapWorld.small(w.source.envelope()));
        return RoutesphereKit.fromBootstrap(w.source, "ad-sphere", w.makers());
    }

    private RoutesphereEngine<LabContext> engineOn(RoutesphereKit kit, TestChannel<String, StatemachineEvent> wire) {
        return RoutesphereEngine.of(new LabSupervisor(new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()))).signaling(LabCall.spec())
            .channel(wire, (id, frame) -> Delivery.to(LabCall.TYPE, frame)).start();
    }

    @Test
    void theLanesSinks_standBesideEveryPartTheRoadMade() {
        RoutesphereKit road = fromTheBootstrap();
        SdrSink adSdr = (id, record) -> { };
        TaskSink taskStore = (ctx, task) -> { };

        RoutesphereKit lane = road.toBuilder().sdr(adSdr).tasks(taskStore).build();

        assertThat(lane.sdr()).isSameAs(adSdr);
        assertThat(lane.tasks()).isSameAs(taskStore);
        assertThat(lane.tenants()).as("the trees").isSameAs(road.tenants());
        assertThat(lane.resolver()).as("the doors").isSameAs(road.resolver());
        assertThat(lane.ledger()).as("every root's ledger").isSameAs(road.ledger());
        assertThat(lane.cdr()).as("every root's CDR road").isSameAs(road.cdr());
        assertThat(lane.journal()).as("every root's journal").isSameAs(road.journal());
        assertThat(lane.settings()).as("the bootstrap's pool and timers").isSameAs(road.settings());
        assertThat(lane.zone()).isEqualTo(road.zone());
        assertThat(lane.bootstrapRoad()).as("the road itself: the engine starts and closes it").containsSame(road.bootstrapRoad().orElseThrow());
        assertThat(lane.deployment().orElseThrow()).isSameAs(road.deployment().orElseThrow());
    }

    @Test
    void aSessionOnTheLanesKit_writesItsRecordToTheLanesSink_itsCdrOnTheRootsRoad_andTheEnginesCloseClosesTheRoad() throws Exception {
        List<Object> records = new CopyOnWriteArrayList<>();
        RoutesphereKit lane = fromTheBootstrap().toBuilder().sdr((id, record) -> records.add(record)).build();
        w.ledgers.get("btcl").fund("btcl", 1, "100.00");
        TestChannel<String, StatemachineEvent> wire = new TestChannel<>("lab");
        engine = engineOn(lane, wire);

        long now = System.currentTimeMillis();
        frame(wire, "v-1", new Dial(new LabFacts("v-1", now, Ingress.listen("10.10.188.2", 7070), 1, "8801711000000")));
        frame(wire, "v-1", new Answer(now));
        frame(wire, "v-1", new Hangup(now));

        assertThat(records).as("the lane's own SDR sink").singleElement().asString().startsWith("sdr:v-1:SUCCEEDED");
        assertThat(w.kafka.producers.get(BTCL_KAFKA).history()).extracting(ProducerRecord::topic).as("the road's CDR road").containsExactly("cdr_btcl");
        engine.close();
        engine = null;
        assertThat(w.kafka.producers.get(BTCL_KAFKA).closed()).as("the road's producer, closed by the engine on the lane's kit").isTrue();
        assertThat(w.kafka.consumers.get(BTCL_KAFKA).closed()).as("the road's tenant_created listener").isTrue();
    }

    @Test
    void aKitBuiltByHand_isCarriedWholeToo_itsClockAndZone() {
        ManualClock clock = new ManualClock(Instant.parse("2026-10-24T00:00:00Z"), ZoneId.of("UTC"));
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of()).ledger(new InMemoryLedger()).cdr(new RecordingCdrSink())
            .zone(ZoneId.of("Asia/Dhaka")).clock(clock).build();
        RoutesphereKit copy = kit.toBuilder().build();
        assertThat(copy.clock()).isSameAs(clock);
        assertThat(copy.zone()).isEqualTo(ZoneId.of("Asia/Dhaka"));
        assertThat(copy.bootstrapRoad()).isEmpty();
    }

    @Test
    void aKitAnEngineRunsOn_isNotCopied_inWords() {
        RoutesphereKit kit = fromTheBootstrap();
        engine = engineOn(kit, new TestChannel<>("lab"));
        assertThatThrownBy(kit::toBuilder).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("an engine runs on this kit").hasMessageContaining("before the flow");
    }

    private void frame(TestChannel<String, StatemachineEvent> wire, String id, StatemachineEvent e) throws Exception {
        wire.inject(id, e).toCompletableFuture().get(5, TimeUnit.SECONDS);
        engine.awaitSettled(id, 5, TimeUnit.SECONDS);
        engine.registry().awaitIdle(5, TimeUnit.SECONDS);
    }
}
