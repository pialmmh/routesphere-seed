package com.telcobright.seed.context.internal;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** The floor under a missed doorbell: every tenant reloads at least once per period, from a scheduled ring. */
final class Backstop implements AutoCloseable {

    private final ScheduledExecutorService scheduler;

    Backstop(Duration every, Runnable ringAll) {
        if (every == null || every.isZero() || every.isNegative()) {
            scheduler = null;
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "seed-context-backstop");
            t.setDaemon(true);
            return t;
        });
        long ms = every.toMillis();
        scheduler.scheduleAtFixedRate(ringAll, ms, ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
