package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.naming.api.Topics;
import com.telcobright.seed.sessionflow.api.TenantCreated;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Listens to prime-context's {@code tenant_created} (R10, §C.2) through the kit's Kafka and hands every event to its road: a new root this
 * application serves is loaded live — its tree, its doors, its topics — and served with no restart; a new reseller tier reloads its root's
 * tree. A message that is not §C.2's is one WARN; the next one is read. One thread, its own consumer group (every process hears every event).
 */
public final class TenantCreatedListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TenantCreatedListener.class);
    static final Duration POLL = Duration.ofMillis(500);

    /** What the road does with one event. */
    @FunctionalInterface
    public interface Handler { void onTenantCreated(TenantCreated event); }

    private final Consumer<String, String> consumer;
    private final Handler handler;
    private final String app;
    private volatile boolean running;
    private Thread thread;

    public TenantCreatedListener(Consumer<String, String> consumer, Handler handler, String app) {
        this.consumer = consumer;
        this.handler = handler;
        this.app = app;
        consumer.subscribe(List.of(Topics.TENANT_CREATED));
    }

    /** Listen on a thread of its own until {@link #close}. */
    public TenantCreatedListener start() {
        running = true;
        thread = new Thread(this::listen, app + "-tenant-created");
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    /** One poll: every event it brought handed to the road (the thread's loop; a test calls it itself). Answers how many were read. */
    public int pollOnce(Duration wait) {
        int read = 0;
        for (ConsumerRecord<String, String> record : consumer.poll(wait)) {
            read++;
            handOver(record);
        }
        return read;
    }

    private void listen() {
        try {
            while (running) pollOnce(POLL);
        } catch (WakeupException e) {
            if (running) throw e;
        } catch (RuntimeException e) {
            log.error("[{}] the {} listener stopped: {} — a tenant created from now on needs a restart to be served", app, Topics.TENANT_CREATED, e.toString());
        }
    }

    private void handOver(ConsumerRecord<String, String> record) {
        try {
            handler.onTenantCreated(TenantCreated.parse(record.value()));
        } catch (RuntimeException e) {
            log.warn("[{}] {} message (key {}) not served: {}", app, Topics.TENANT_CREATED, record.key(), e.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            consumer.wakeup();
            try { thread.join(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        consumer.close();
    }
}
