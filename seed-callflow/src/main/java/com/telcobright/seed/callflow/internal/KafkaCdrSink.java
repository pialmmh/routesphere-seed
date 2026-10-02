package com.telcobright.seed.callflow.internal;

import com.telcobright.seed.callflow.api.CdrEvent;
import com.telcobright.seed.callflow.spi.CdrSink;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * The CDR road of every switch: one Kafka message per ended call on the tenant tree's {@code cdr} topic — the key is the
 * call id, the value is the JSON array of the call's tier records. Sent with {@code acks=all} and idempotence: at least
 * once, and billing-core takes a call once per tier however often it arrives.
 *
 * <p>The send never blocks the call's thread beyond the producer's own short bound. A record the broker did not take
 * within the delivery timeout is ONE error line with the call id: the journal still has it.
 */
public final class KafkaCdrSink implements CdrSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaCdrSink.class);

    private final KafkaProducer<String, String> producer;
    private final String topic;

    public KafkaCdrSink(String bootstrapServers, String topic, String clientId) {
        this.producer = new KafkaProducer<>(producerSettings(bootstrapServers, clientId), new StringSerializer(), new StringSerializer());
        this.topic = topic;
        log.info("CDRs go to Kafka {} topic {} (acks=all, idempotent)", bootstrapServers, topic);
    }

    @Override
    public void publish(String callId, List<CdrEvent> tiers) {
        try {
            producer.send(new ProducerRecord<>(topic, callId, CdrJson.ofCall(tiers)), (metadata, failure) -> {
                if (failure != null) log.error("the CDR of call {} was NOT published to {}: {}", callId, topic, failure.toString());
            });
        } catch (RuntimeException e) {
            log.error("the CDR of call {} could NOT be sent to {}: {}", callId, topic, e.toString());
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
