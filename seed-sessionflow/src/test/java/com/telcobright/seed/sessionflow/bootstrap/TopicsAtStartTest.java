package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.seed.naming.api.Direction;
import com.telcobright.seed.naming.api.Topics;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.base.LabCall;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabFlow;
import com.telcobright.seed.sessionflow.base.LabSupervisor;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-E item 7 · the engine on a kit made from the bootstrap makes, at its start, every served root's topics (R9): its CDR road
 * {@code cdr_<root>}, its doorbell {@code config_event_loader_<root>}, and the lane's own ({@code topicsOf(root)}: {@code <app>_<in|out>_<name>_<root>}).
 * A kit built by hand makes none (it has no road to a cluster).
 */
class TopicsAtStartTest {

    private final BootstrapWorld w = new BootstrapWorld();
    private RoutesphereEngine<LabContext> engine;

    @AfterEach
    void stop() { if (engine != null) engine.close(); }

    /** The lab's lane, with one topic of its own per root. */
    static final class LaneWithItsOwnTopic extends LabFlow {
        LaneWithItsOwnTopic(RoutesphereKit kit) { super(kit, Map.of(), List.of(), Set.of()); }
        @Override protected List<String> topicsOf(String root) { return List.of(Topics.app("lab", Direction.OUT, "calls", root)); }
    }

    private RoutesphereEngine<LabContext> start(RoutesphereKit kit) {
        engine = RoutesphereEngine.of(new LabSupervisor(new LaneWithItsOwnTopic(kit))).signaling(LabCall.spec())
            .channel(new TestChannel<String, StatemachineEvent>("lab"), (id, frame) -> Delivery.to(LabCall.TYPE, frame)).start();
        return engine;
    }

    @Test
    void theStart_makesEveryServedRootsTopics_theCdrRoadTheDoorbellAndTheLanesOwn() {
        w.source.serve(BootstrapWorld.small(w.source.envelope()));
        start(RoutesphereKit.fromBootstrap(w.source, "ad-sphere", w.makers()));
        assertThat(w.kafka.topics.get("10.10.188.2:9092")).containsExactlyInAnyOrder(
            "cdr_btcl", "config_event_loader_btcl", "lab_out_calls_btcl", "cdr_link3", "config_event_loader_link3", "lab_out_calls_link3");
    }

    @Test
    void aRefusingCluster_doesNotStopTheStart() {
        w.kafka.refusing("10.10.188.2:9092");
        w.source.serve(BootstrapWorld.small(w.source.envelope()));
        assertThat(start(RoutesphereKit.fromBootstrap(w.source, "ad-sphere", w.makers())).registry()).isNotNull();
        assertThat(w.kafka.asks.get("10.10.188.2:9092")).hasSize(2);
    }
}
