package com.telcobright.seed.tenant.internal;

import com.telcobright.seed.context.spi.TopicNaming;
import com.telcobright.seed.tenant.api.Doorbell;
import com.telcobright.seed.tenant.api.TenantTrees;
import com.telcobright.seed.tenant.dependencies.TenantTreesBuilder;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

/**
 * The ringer: the local ring first (this instance never waits for its own message), then one record on the tenant's topic with
 * {@code acks=all}. A producer that fails is logged, never thrown into the writer's road: the write is committed either way.
 */
public final class KafkaDoorbell implements Doorbell {

    private static final Logger log = LoggerFactory.getLogger(KafkaDoorbell.class);

    private final TenantTrees trees;
    private final Producer<String, String> producer;     // null = local only
    private final String service;
    private final TopicNaming naming = TopicNaming.underscore();

    /** The producer handed in: a test's {@code MockProducer}; null = local rings only. */
    public KafkaDoorbell(TenantTrees trees, Producer<String, String> producer, String service) {
        this.trees = trees;
        this.producer = producer;
        this.service = service;
        if (producer == null) log.warn("no broker for the doorbell — a write of '{}' reloads THIS instance only", service);
    }

    public static KafkaDoorbell of(TenantTrees trees, String bootstrap, String service) {
        if (bootstrap == null || bootstrap.isBlank()) return new KafkaDoorbell(trees, null, service);
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000);
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, service + "-doorbell");
        return new KafkaDoorbell(trees, new KafkaProducer<>(p, new StringSerializer(), new StringSerializer()), service);
    }

    @Override
    public boolean ring(String tenantId, String what) {
        trees.ring(tenantId, service + ":" + what);
        if (producer == null) return false;
        String topic = naming.topic(TenantTreesBuilder.DOORBELL_BASE, tenantId);
        String json = "{\"tenantId\":\"" + tenantId + "\",\"source\":\"" + service + "\",\"what\":\"" + what + "\",\"at\":\"" + Instant.now() + "\"}";
        try {
            producer.send(new ProducerRecord<>(topic, tenantId, json), (md, ex) -> {
                if (ex != null) log.error("doorbell for {} not rung on {} after a write ({}): {}", tenantId, topic, what, ex.toString());
            });
            return true;
        } catch (RuntimeException e) {
            log.error("doorbell for {} could not be rung on {} ({}): {}", tenantId, topic, what, e.toString());
            return false;
        }
    }

    @Override
    public void close() {
        if (producer != null) try { producer.close(Duration.ofSeconds(5)); } catch (RuntimeException e) { log.warn("doorbell producer close: {}", e.toString()); }
    }
}
