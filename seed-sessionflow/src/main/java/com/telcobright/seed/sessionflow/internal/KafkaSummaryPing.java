package com.telcobright.seed.sessionflow.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.sessionflow.spi.SummaryPing;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The ping on Kafka, as billing-core's {@code SummaryChangeNotificationPublisher} sends it: on ONE topic ({@code cdr_summary_ping}, or the
 * deployment's {@code cdr_summary_ping_<root>}), no key, the value {@code {"tenant": <schema>, "entity": "cdr", "rows": n}} — what
 * summary-service's {@code PingPayload.parse} reads. A ping the brokers did not take is one WARN line; the rows are written and polled.
 */
public final class KafkaSummaryPing implements SummaryPing, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaSummaryPing.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** billing-core's {@code BrokerWaitMs}: the most a send waits for the brokers, on the ping's own thread. */
    private static final long BROKER_WAIT_MS = 10_000;

    private final Producer<String, String> producer;
    private final String topic;

    public KafkaSummaryPing(Producer<String, String> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    public static KafkaSummaryPing of(String bootstrapServers, String topic, String clientId) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, clientId + "-summary-ping");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, BROKER_WAIT_MS);
        return new KafkaSummaryPing(new KafkaProducer<>(p, new StringSerializer(), new StringSerializer()), topic);
    }

    @Override
    public void ping(String tenant, String entity, int rows) {
        try {
            producer.send(new ProducerRecord<>(topic, null, payloadOf(tenant, entity, rows)), (sent, failure) -> {
                if (failure != null) log.warn("the summary ping of {} was not taken by {}: {} — the rows are written, summary-service polls", tenant, topic, failure.toString());
            });
        } catch (RuntimeException e) {
            log.warn("the summary ping of {} could not be sent to {}: {} — the rows are written, summary-service polls", tenant, topic, e.toString());
        }
    }

    /** billing-core's {@code PayloadOf}: the three names, never hand-built JSON. */
    public static String payloadOf(String tenant, String entity, int rows) {
        Map<String, Object> ping = new LinkedHashMap<>();
        ping.put("tenant", tenant == null ? "" : tenant);
        ping.put("entity", entity == null ? "" : entity);
        ping.put("rows", rows);
        try {
            return JSON.writeValueAsString(ping);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    @Override
    public void close() {
        try {
            producer.close(java.time.Duration.ofSeconds(5));
        } catch (RuntimeException e) {
            log.warn("the summary ping's producer did not close cleanly: {}", e.toString());
        }
    }
}
