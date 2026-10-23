package com.telcobright.seed.sessionflow;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.dependencies.CdrSinks;
import com.telcobright.seed.sessionflow.internal.KafkaCdrSink;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-F item 3 · several sinks as one ({@code CdrSinks.all}) keep the session's ROOT: every sink is handed the root the session named, so a
 * per-root sink behind the fan-out publishes on {@code cdr_<root>} — before, it was handed the root-less call and published nothing.
 */
class CdrFanOutTest {

    /** A sink that writes down which road each message came on. */
    static final class RoadsSeen implements CdrSink {
        final List<String> seen = new CopyOnWriteArrayList<>();
        @Override public void publish(String callId, List<CdrEvent> tiers) { seen.add("no root:" + callId); }
        @Override public void publish(String root, String callId, List<CdrEvent> tiers) { seen.add(root + ":" + callId); }
    }

    @Test
    void theRootReachesEverySink_andARootlessCallStaysRootless() {
        RoadsSeen journal = new RoadsSeen(), kafka = new RoadsSeen();
        CdrSink both = CdrSinks.all(journal, kafka);
        both.publish("btcl", "c-1", List.of());
        both.publish("c-2", List.of());
        assertThat(journal.seen).containsExactly("btcl:c-1", "no root:c-2");
        assertThat(kafka.seen).containsExactly("btcl:c-1", "no root:c-2");
    }

    @Test
    void aPerRootKafkaSinkBehindTheFanOut_publishesOnTheRootsTopic() {
        MockProducer<String, String> producer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        CdrSink road = CdrSinks.all(new RoadsSeen(), KafkaCdrSink.perRoot(producer, "cdr_"));
        road.publish("link3", "s-1", List.of(new CdrEvent()));
        assertThat(producer.history()).extracting(r -> r.topic() + ":" + r.key()).containsExactly("cdr_link3:s-1");
    }

    @Test
    void oneSinksFailure_neverStopsTheNext_onTheRootsRoadToo() {
        RoadsSeen kafka = new RoadsSeen();
        CdrSink failing = new CdrSink() {
            @Override public void publish(String callId, List<CdrEvent> tiers) { throw new IllegalStateException("scripted"); }
            @Override public void publish(String root, String callId, List<CdrEvent> tiers) { throw new IllegalStateException("scripted"); }
        };
        CdrSinks.all(failing, kafka).publish("link3", "c-3", List.of());
        assertThat(kafka.seen).containsExactly("link3:c-3");
    }
}
