package com.telcobright.seed.configclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trailing-edge debounce: every {@link #ring(String)} restarts the quiet period; the action fires
 * once, after the ringing stops for {@code debounceMs}. This is the routesphere reload semantic —
 * a burst of CDC notifications becomes one re-fetch.
 *
 * The action runs on the gate's own single thread; a throwing action is logged, never propagated,
 * and never kills the gate.
 */
public final class DebounceGate implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DebounceGate.class);

    private final long debounceMs;
    private final Runnable action;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong rings = new AtomicLong();
    private final AtomicLong fires = new AtomicLong();
    private ScheduledFuture<?> pending;   // guarded by this

    public DebounceGate(long debounceMs, Runnable action) {
        if (debounceMs < 0) throw new IllegalArgumentException("debounceMs must be >= 0");
        this.debounceMs = debounceMs;
        this.action = action;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "config-doorbell-debounce");
            t.setDaemon(true);
            return t;
        });
    }

    /** A notification arrived. {@code source} is only for the log line ("kafka", "redis"). */
    public synchronized void ring(String source) {
        rings.incrementAndGet();
        if (pending != null) pending.cancel(false);
        log.debug("doorbell ring from {} — (re)arming {}ms quiet period", source, debounceMs);
        pending = scheduler.schedule(this::fire, debounceMs, TimeUnit.MILLISECONDS);
    }

    private void fire() {
        fires.incrementAndGet();
        try {
            action.run();
        } catch (Exception e) {
            log.error("doorbell action failed (gate stays alive)", e);
        }
    }

    public long ringCount() { return rings.get(); }

    public long fireCount() { return fires.get(); }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
