package com.telcobright.seed.context.internal;

import com.telcobright.seed.configclient.DebounceGate;
import com.telcobright.seed.context.api.ContextStats;
import com.telcobright.seed.context.api.Snapshot;
import com.telcobright.seed.context.publishes.ContextEvent;
import com.telcobright.seed.context.publishes.ContextListener;
import com.telcobright.seed.context.spi.ContextLoader;
import com.telcobright.seed.context.spi.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One tenant: its snapshot, its debounce gate, its single-flight reload, its counters. A load runs on a
 * virtual thread; the swap is one reference write; a failed load keeps the last good snapshot; a load
 * that outlives the timeout is failed, and its late result can never swap (the load's future is already
 * settled by then).
 */
final class TenantCell<T> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TenantCell.class);

    final String tenantId;
    private final ContextLoader<T> loader;
    private final SecretResolver secrets;
    private final ContextListener listener;
    private final Duration loadTimeout;
    private final DebounceGate gate;

    private final AtomicReference<Snapshot<T>> current = new AtomicReference<>();
    private final AtomicLong version = new AtomicLong();
    private final AtomicLong loads = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong rings = new AtomicLong();
    private volatile String lastFailure;
    private volatile long lastLoadMs;

    // single-flight, guarded by this: the caller-visible future of the load in flight, and of the one queued behind it
    private long sequence;
    private CompletableFuture<Snapshot<T>> inFlight;
    private CompletableFuture<Snapshot<T>> queued;
    private String queuedReason;
    private boolean closed;

    TenantCell(String tenantId, ContextLoader<T> loader, SecretResolver secrets, ContextListener listener,
               long debounceMs, Duration loadTimeout) {
        this.tenantId = tenantId;
        this.loader = loader;
        this.secrets = secrets;
        this.listener = listener;
        this.loadTimeout = loadTimeout;
        this.gate = new DebounceGate(debounceMs, () -> reload("doorbell"));
    }

    Snapshot<T> current() { return current.get(); }

    /** A ring restarts the quiet period; the reload comes once, after the ringing stops. */
    void ring(String source) {
        rings.incrementAndGet();
        gate.ring(source);
    }

    /** Single-flight: a load in flight takes one more run after it, and every caller meanwhile gets that run. */
    synchronized CompletableFuture<Snapshot<T>> reload(String reason) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("tenant " + tenantId + " dropped"));
        if (inFlight != null) {
            if (queued == null) { queued = new CompletableFuture<>(); queuedReason = reason; }
            return queued;
        }
        // a load that completes before whenComplete is attached runs loadEnded reentrantly, right here — so the
        // field may already be null again by the time we return; the local is what the caller gets
        CompletableFuture<Snapshot<T>> mine = new CompletableFuture<>();
        inFlight = mine;
        startLoad(mine, reason, ++sequence);
        return mine;
    }

    /** The raw load result is its own future, so the caller's future only ever completes with a stamped snapshot. */
    private void startLoad(CompletableFuture<Snapshot<T>> callers, String reason, long seq) {
        long startedAt = System.nanoTime();
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.ofVirtual().name("seed-context-" + tenantId).start(() -> runLoad(result));
        result.orTimeout(loadTimeout.toMillis(), TimeUnit.MILLISECONDS)
              .whenComplete((context, error) -> loadEnded(callers, seq, context, error, reason, startedAt));
    }

    private void runLoad(CompletableFuture<T> result) {
        try {
            T context = loader.load(tenantId, secrets);
            if (context == null) throw new IllegalStateException("loader returned null");
            result.complete(context);                           // false when the timeout won: the result is dropped
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            result.completeExceptionally(t);
        }
    }

    /** Once per load: a swap or a recorded failure, the caller's future settled, then the queued run if any. */
    private void loadEnded(CompletableFuture<Snapshot<T>> callers, long seq, T context, Throwable error, String reason, long startedAt) {
        long ms = (System.nanoTime() - startedAt) / 1_000_000;
        CompletableFuture<Snapshot<T>> next;
        String nextReason;
        synchronized (this) {
            if (closed || seq != sequence) { callers.completeExceptionally(new IllegalStateException("tenant " + tenantId + " dropped")); return; }
            if (error == null) callers.complete(swap(context, reason, ms));
            else callers.completeExceptionally(fail(error, reason));
            inFlight = null;
            next = queued; nextReason = queuedReason;
            queued = null; queuedReason = null;
            if (next != null) { inFlight = next; startLoad(next, nextReason, ++sequence); }
        }
    }

    private Snapshot<T> swap(T context, String reason, long ms) {
        Snapshot<T> stamped = new Snapshot<>(tenantId, version.incrementAndGet(), Instant.now(), context);
        current.set(stamped);                                   // the one write every reader sees
        loads.incrementAndGet();
        lastLoadMs = ms;
        listener.on(new ContextEvent.Reloaded(tenantId, stamped.version(), ms, reason));
        log.info("context {} v{} loaded in {} ms ({})", tenantId, stamped.version(), ms, reason);
        return stamped;
    }

    private Throwable fail(Throwable error, String reason) {
        failures.incrementAndGet();
        Throwable cause = error instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : error;
        String why = cause instanceof TimeoutException ? "load exceeded " + loadTimeout : cause.toString();
        lastFailure = why;
        Snapshot<T> serving = current.get();
        listener.on(new ContextEvent.LoadFailed(tenantId, serving == null ? 0 : serving.version(), reason, why));
        if (serving == null) log.error("context {} has NO snapshot — load failed ({}): {}", tenantId, reason, why);
        else log.warn("context {} keeps v{} — load failed ({}): {}", tenantId, serving.version(), reason, why);
        return cause;
    }

    ContextStats stats() {
        Snapshot<T> s = current.get();
        return new ContextStats(tenantId, s != null, s == null ? 0 : s.version(), s == null ? null : s.loadedAt(),
            loads.get(), failures.get(), lastFailure, lastLoadMs, rings.get());
    }

    String lastFailure() { return lastFailure; }

    @Override
    public synchronized void close() {
        closed = true;
        gate.close();
        if (queued != null) { queued.completeExceptionally(new IllegalStateException("tenant " + tenantId + " dropped")); queued = null; }
        current.set(null);
    }
}
