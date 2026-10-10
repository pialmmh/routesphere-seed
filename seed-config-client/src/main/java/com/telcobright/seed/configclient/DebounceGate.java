package com.telcobright.seed.configclient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trailing-edge debounce WITH A CAP: every {@link #ring(String)} restarts the quiet period and the action fires once after the ringing
 * stops for {@code debounceMs} — a burst of CDC notifications becomes one re-fetch. The cap (ARCH-0077-A item 7; routesphere-core
 * {@code ConfigEventConsumer.scheduleReload}, 560–601: "first event after idle — no reload in the last 2× debounce window — triggers
 * immediately"): when the last FIRE is older than {@code 2 × debounceMs}, a ring fires AT ONCE (delay 0) instead of re-arming, so a dense
 * stream — a ring every second, each re-arming the quiet period — cannot starve the gate forever: it fires about every 2 × debounceMs
 * while the stream lasts, and once more when it ends. The gate is born as if it had just fired (the product has just loaded its config):
 * a burst right after the start still collapses to one trailing fire.
 *
 * The action runs on the gate's own single thread; a throwing action is logged, never propagated, and never kills the gate.
 */
public final class DebounceGate implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DebounceGate.class);

    private final long debounceMs;
    private final Runnable action;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong rings = new AtomicLong();
    private final AtomicLong fires = new AtomicLong();
    private volatile long lastFireMs;
    private ScheduledFuture<?> pending;   // guarded by this

    public DebounceGate(long debounceMs, Runnable action) {
        if (debounceMs < 0) throw new IllegalArgumentException("debounceMs must be >= 0");
        this.debounceMs = debounceMs;
        this.action = action;
        this.lastFireMs = System.currentTimeMillis();
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
        long delay = idle() ? 0 : debounceMs;
        log.debug("doorbell ring from {} — {}", source, delay == 0 ? "idle: firing at once" : "(re)arming " + debounceMs + "ms quiet period");
        pending = scheduler.schedule(this::fire, delay, TimeUnit.MILLISECONDS);
    }

    /** The cap: no fire in the last 2 × debounceMs. */
    private boolean idle() {
        return System.currentTimeMillis() - lastFireMs > 2 * debounceMs;
    }

    private void fire() {
        lastFireMs = System.currentTimeMillis();
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
