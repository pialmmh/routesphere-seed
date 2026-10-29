package com.telcobright.seed.configclient;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The reliable doorbell leg: a Kafka consumer on the notification topic. Message content is
 * ignored by design — an event only means "something changed, re-fetch".
 *
 * Every instance gets its OWN consumer group by default (uuid suffix) so every gateway hears
 * every notification — this is a broadcast, not a work queue.
 *
 * Connection loss is survived: the poll loop logs, waits, and retries forever. Set
 * {@code failFast} only where a missing broker should abort startup (routesphere-core does).
 */
final class KafkaDoorbellSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaDoorbellSource.class);
    private static final long RETRY_BACKOFF_MS = 5_000;

    private final String bootstrap;
    private final String topic;
    private final String groupId;
    private final boolean failFast;
    private final DebounceGate gate;
    private final Supplier<Consumer<String, String>> consumers;
    private final PollStopper stopper = new PollStopper();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;

    KafkaDoorbellSource(String bootstrap, String topic, String groupId, boolean failFast, DebounceGate gate) {
        this(bootstrap, topic, groupId, failFast, gate, null);
    }

    /** The same, with the consumers made by {@code consumers} (a test hands a mock); null = a KafkaConsumer on {@code bootstrap}. */
    KafkaDoorbellSource(String bootstrap, String topic, String groupId, boolean failFast, DebounceGate gate,
                        Supplier<Consumer<String, String>> consumers) {
        this.bootstrap = bootstrap;
        this.topic = topic;
        this.groupId = groupId != null ? groupId : "config-doorbell-" + UUID.randomUUID();
        this.failFast = failFast;
        this.gate = gate;
        this.consumers = consumers != null ? consumers : () -> new KafkaConsumer<>(props());
        this.thread = new Thread(this::run, "config-doorbell-kafka");
        this.thread.setDaemon(true);
    }

    void start() { thread.start(); }

    private void run() {
        while (running.get()) {
            try {
                listenWithOneConsumer();
            } catch (WakeupException e) {
                // only close() wakes the consumer: the loop's condition ends the thread
            } catch (Exception e) {
                if (!running.get()) return;
                if (failFast) throw new IllegalStateException("kafka doorbell failed (failFast)", e);
                log.warn("kafka doorbell lost ({}) — retrying in {}ms", e.getMessage(), RETRY_BACKOFF_MS);
                sleep(RETRY_BACKOFF_MS);
            }
        }
    }

    /** One consumer's life: subscribe, poll until stopped, and always close it here, on the poll thread, never interrupted. */
    private void listenWithOneConsumer() {
        Consumer<String, String> consumer = consumers.get();
        stopper.polling(consumer);
        try {
            consumer.subscribe(List.of(topic));
            log.info("doorbell listening on kafka {} topic={} group={}", bootstrap, topic, groupId);
            while (running.get()) {
                if (!consumer.poll(Duration.ofMillis(1_000)).isEmpty()) gate.ring("kafka");
            }
        } finally {
            stopper.released();
            PollStopper.closeQuietly(consumer, log);
        }
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

    /** Stops the poll thread by waking its consumer (see {@link PollStopper}); returns when the consumer is closed. */
    @Override
    public void close() {
        running.set(false);
        stopper.stop(thread, log);
    }

    /** For a test: whether the poll thread still runs. */
    boolean polling() { return thread.isAlive(); }
}
