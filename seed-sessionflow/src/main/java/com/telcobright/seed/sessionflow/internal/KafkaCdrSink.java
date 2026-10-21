package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * The CDR road of every switch: one Kafka message per ended session — the key is the session id, the value is the JSON array of its tier
 * records. Sent with {@code acks=all} and idempotence: at least once, and billing-core takes a session once per tier however often it
 * arrives. Two shapes: ONE topic for every session ({@link #KafkaCdrSink(String, String, String)}), or the session's ROOT names the topic
 * ({@link #perRoot}: {@code <prefix><root>}, the deployed {@code cdr_<root>} — §C: every lookup of a session is scoped to its root, its
 * CDR's road too).
 *
 * <p>The send never blocks the session's thread beyond the producer's own short bound. A record the broker did not take within the
 * delivery timeout is ONE error line with the session id.
 */
public final class KafkaCdrSink implements CdrSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaCdrSink.class);

    private final Producer<String, String> producer;
    private final String topic;
    private final String rootTopicPrefix;

    /** Every session on one topic. */
    public KafkaCdrSink(String bootstrapServers, String topic, String clientId) {
        this(new KafkaProducer<>(producerSettings(bootstrapServers, clientId), new StringSerializer(), new StringSerializer()), topic, null);
        log.info("CDRs go to Kafka {} topic {} (acks=all, idempotent)", bootstrapServers, topic);
    }

    /** For the sink's own tests and for a host that owns its producer: the topic, or the root's prefix (one of the two). */
    KafkaCdrSink(Producer<String, String> producer, String topic, String rootTopicPrefix) {
        if ((topic == null) == (rootTopicPrefix == null)) throw new IllegalArgumentException("a CDR sink names either its one topic or the root's prefix");
        this.producer = producer;
        this.topic = topic;
        this.rootTopicPrefix = rootTopicPrefix;
    }

    /** The session's root names the topic: {@code prefix + root} (the deployed {@code cdr_<root>}). */
    public static KafkaCdrSink perRoot(String bootstrapServers, String prefix, String clientId) {
        KafkaCdrSink sink = new KafkaCdrSink(new KafkaProducer<>(producerSettings(bootstrapServers, clientId), new StringSerializer(), new StringSerializer()), null, prefix);
        log.info("CDRs go to Kafka {} topic {}<root> (acks=all, idempotent)", bootstrapServers, prefix);
        return sink;
    }

    /** {@link #perRoot} on a producer the caller owns (a test's {@code MockProducer}). */
    public static KafkaCdrSink perRoot(Producer<String, String> producer, String prefix) { return new KafkaCdrSink(producer, null, prefix); }

    @Override
    public void publish(String callId, List<CdrEvent> tiers) {
        if (topic == null) {
            log.error("the CDR of session {} names no root and this sink's topic is per root ({}<root>): NOT published", callId, rootTopicPrefix);
            return;
        }
        send(topic, callId, tiers);
    }

    @Override
    public void publish(String root, String callId, List<CdrEvent> tiers) {
        if (rootTopicPrefix == null) { send(topic, callId, tiers); return; }
        if (root == null || root.isBlank()) { publish(callId, tiers); return; }
        send(rootTopicPrefix + root, callId, tiers);
    }

    private void send(String to, String callId, List<CdrEvent> tiers) {
        try {
            producer.send(new ProducerRecord<>(to, callId, CdrJson.ofCall(tiers)), (metadata, failure) -> {
                if (failure != null) log.error("the CDR of session {} was NOT published to {}: {}", callId, to, failure.toString());
            });
        } catch (RuntimeException e) {
            log.error("the CDR of session {} could NOT be sent to {}: {}", callId, to, e.toString());
        }
    }

    @Override
    public void close() {
        try {
            producer.close(Duration.ofSeconds(5));
        } catch (RuntimeException e) {
            log.warn("the CDR producer did not close cleanly: {}", e.toString());
        }
    }

    private static Properties producerSettings(String bootstrapServers, String clientId) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, clientId + "-cdr");
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 60_000);
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        p.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000);
        return p;
    }
}
