package com.telcobright.seed.configclient;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The per-tenant doorbell leg: ONE Kafka consumer on the EXACT list of the tenants' topics
 * ({@code <base>_<tenantId>} — never a pattern, the standing rule since the 2026-08-18 rogue-consumer
 * incident), telling the caller WHICH topic rang. Message content is ignored; the topic names the tenant.
 * {@link #subscribe(List)} may be called at any time (a tenant joined or left); the poll loop picks the
 * new list up on its next turn.
 *
 * <p>Like {@link KafkaDoorbellSource}: its own consumer group by default (a broadcast, not a work queue),
 * connection loss survived by retrying forever unless {@code failFast}.
 */
public final class KafkaTopicsDoorbellSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicsDoorbellSource.class);
    private static final long RETRY_BACKOFF_MS = 5_000;

    private final String bootstrap;
    private final String groupId;
    private final boolean failFast;
    private final Consumer<String> onTopicRing;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;
    private volatile List<String> wanted = List.of();
    private volatile boolean started;

    public KafkaTopicsDoorbellSource(String bootstrap, String groupId, boolean failFast, Consumer<String> onTopicRing) {
        this.bootstrap = bootstrap;
        this.groupId = groupId != null ? groupId : "config-doorbell-" + UUID.randomUUID();
        this.failFast = failFast;
        this.onTopicRing = onTopicRing;
        this.thread = new Thread(this::run, "config-doorbell-kafka-topics");
        this.thread.setDaemon(true);
    }

    /** The exact topics to listen on from now; an empty list means listen to nothing until the next call. */
    public void subscribe(List<String> topics) {
        wanted = List.copyOf(topics);
    }

    public List<String> subscribed() { return wanted; }

    public synchronized void start() {
        if (started) return;
        started = true;
        thread.start();
    }

    private void run() {
        while (running.get()) {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props())) {
                pollUntilStopped(consumer);
            } catch (Exception e) {
                if (!running.get()) return;
                if (failFast) throw new IllegalStateException("kafka doorbell failed (failFast)", e);
                log.warn("kafka doorbell lost ({}) — retrying in {}ms", e.getMessage(), RETRY_BACKOFF_MS);
                sleep(RETRY_BACKOFF_MS);
            }
        }
    }

    private void pollUntilStopped(KafkaConsumer<String, String> consumer) {
        Set<String> current = new HashSet<>();
        while (running.get()) {
            current = followSubscription(consumer, current);
            if (current.isEmpty()) { sleep(500); continue; }
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1_000));
            Set<String> rang = new HashSet<>();
            for (ConsumerRecord<String, String> r : records) rang.add(r.topic());
            rang.forEach(this::ring);
        }
    }

    /** Re-subscribe only when the wanted list changed: a subscribe() call is a rebalance, not free. */
    private Set<String> followSubscription(KafkaConsumer<String, String> consumer, Set<String> current) {
        Set<String> want = new HashSet<>(wanted);
        if (want.equals(current)) return current;
        if (want.isEmpty()) consumer.unsubscribe();
        else consumer.subscribe(List.copyOf(want));
        log.info("doorbell listening on kafka {} topics={} group={}", bootstrap, want, groupId);
        return want;
    }

    private void ring(String topic) {
        try { onTopicRing.accept(topic); }
        catch (RuntimeException e) { log.error("doorbell ring for {} failed (source stays alive)", topic, e); }
    }

    private Properties props() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");   // doorbells have no history value
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        return p;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    @Override
    public void close() {
        running.set(false);
        thread.interrupt();
    }
}
