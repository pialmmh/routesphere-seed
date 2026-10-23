package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.internal.KafkaSummaryPing;
import com.telcobright.seed.sessionflow.spi.SummaryPing;
import org.apache.kafka.clients.producer.Producer;

/** The summary pings a host can hand its records road (P1-R §B). */
public final class SummaryPings {

    private SummaryPings() {}

    /** billing-core's ping on Kafka: one topic, no key, {@code {"tenant", "entity", "rows"}}. */
    public static SummaryPing kafka(String bootstrapServers, String topic, String clientId) { return KafkaSummaryPing.of(bootstrapServers, topic, clientId); }

    /** The same on a producer the host owns (a test's {@code MockProducer}). */
    public static SummaryPing kafka(Producer<String, String> producer, String topic) { return new KafkaSummaryPing(producer, topic); }

    /** No ping: summary-service polls. */
    public static SummaryPing none() { return SummaryPing.NONE; }
}
