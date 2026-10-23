package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.spi.SummaryPing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The ping's own thread (P1-R §B), billing-core's rule: "the ping never holds a batch" — a writer hands its pings here after its commit and
 * goes on; only this thread talks to the port. A full queue drops the newest ping and says so at most once a minute: a ping is a nudge, the
 * outbox row is the truth. No port = no thread, said once.
 */
public final class PingRoad implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PingRoad.class);
    /** billing-core's {@code QueueCapacity}. */
    static final int QUEUE = 256;
    static final long SAY_EVERY_MS = 60_000;
    public static final String ENTITY = "cdr";

    private record Ping(String tenant, int rows) {}

    private final SummaryPing port;
    private final BlockingQueue<Ping> waiting = new ArrayBlockingQueue<>(QUEUE);
    private final AtomicLong saidAt = new AtomicLong(Long.MIN_VALUE);
    private final Thread thread;
    private volatile boolean stopped;

    public PingRoad(SummaryPing port) {
        this.port = port == null ? SummaryPing.NONE : port;
        this.thread = this.port == SummaryPing.NONE ? null : startTheThread();
        if (thread == null) log.info("the records road has no summary ping: summary-service finds each summary_affected row by its poll");
    }

    /** After a commit: the tier has a new outbox row of {@code rows} records. Never blocks, never throws. */
    public void offer(String tenant, int rows) {
        if (thread == null || stopped) return;
        if (!waiting.offer(new Ping(tenant, rows))) sayADrop(tenant);
    }

    private Thread startTheThread() {
        Thread t = Thread.ofPlatform().name("records-summary-ping").daemon(true).unstarted(this::sendWhatWaits);
        t.start();
        return t;
    }

    private void sendWhatWaits() {
        while (!stopped) {
            try {
                Ping ping = waiting.poll(500, TimeUnit.MILLISECONDS);
                if (ping != null) sendOne(ping);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void sendOne(Ping ping) {
        try {
            port.ping(ping.tenant(), ENTITY, ping.rows());
        } catch (RuntimeException e) {
            log.warn("the summary ping of {} failed: {} — the rows are written, summary-service polls", ping.tenant(), e.toString());
        }
    }

    private void sayADrop(String tenant) {
        long now = System.currentTimeMillis(), last = saidAt.get();
        if (last != Long.MIN_VALUE && now - last < SAY_EVERY_MS) return;
        if (saidAt.compareAndSet(last, now)) log.warn("the summary pings wait {} deep: the ping of {} is dropped — its rows are written, summary-service polls", QUEUE, tenant);
    }

    @Override
    public void close() {
        stopped = true;
        if (thread != null) thread.interrupt();
        if (port instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("the summary ping did not close cleanly: {}", e.toString());
            }
        }
    }
}
