package com.telcobright.seed.switchledger.internal;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * P1-J · A root's merges of its new rows, at most ONE per spacing: the first ask runs the merge at once, on the asking thread (a tree's
 * reload — never a session's); an ask inside the spacing becomes ONE merge at its end, on this class's daemon thread, however many asks
 * the burst had. The thread is made at the first deferred merge and stopped with the port.
 */
final class CoalescedMerges implements AutoCloseable {

    private final long spacingMs;
    private final Runnable merge;
    private final LongSupplier clockMs;
    private final String name;
    private final AtomicLong runs = new AtomicLong();
    private long lastRunMs = Long.MIN_VALUE / 2;
    private boolean deferredMergeWaiting;
    private boolean closed;
    private ScheduledExecutorService timer;

    CoalescedMerges(long spacingMs, Runnable merge, LongSupplier clockMs, String name) {
        this.spacingMs = spacingMs;
        this.merge = merge;
        this.clockMs = clockMs;
        this.name = name;
    }

    /** A tree was reloaded: merge now, or once at the end of the spacing. */
    void ask() {
        if (shouldRunNow()) run();
    }

    /** The merges run so far (the operations' view; a test's). */
    long runs() { return runs.get(); }

    @Override
    public synchronized void close() {
        closed = true;
        if (timer != null) timer.shutdownNow();
    }

    private synchronized boolean shouldRunNow() {
        if (closed) return false;
        long now = clockMs.getAsLong();
        if (now - lastRunMs >= spacingMs) { lastRunMs = now; return true; }
        if (!deferredMergeWaiting) deferUntil(lastRunMs + spacingMs - now);
        return false;
    }

    private void deferUntil(long delayMs) {
        deferredMergeWaiting = true;
        if (timer == null) timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, name + "-merges");
            thread.setDaemon(true);
            return thread;
        });
        timer.schedule(this::runTheDeferredMerge, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    private void runTheDeferredMerge() {
        synchronized (this) {
            deferredMergeWaiting = false;
            if (closed) return;
            lastRunMs = clockMs.getAsLong();
        }
        run();
    }

    private void run() {
        merge.run();
        runs.incrementAndGet();
    }
}
