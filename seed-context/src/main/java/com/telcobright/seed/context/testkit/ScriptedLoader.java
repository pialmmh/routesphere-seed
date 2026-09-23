package com.telcobright.seed.context.testkit;

import com.telcobright.seed.context.spi.ContextLoadFailed;
import com.telcobright.seed.context.spi.ContextLoader;
import com.telcobright.seed.context.spi.SecretResolver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A loader a test scripts per tenant: what to return, whether to fail, how long to take — and how often
 * it was called. The context it builds is a {@code Map<String,Object>} with the tenant id, the call
 * number and whatever the script put there.
 */
public final class ScriptedLoader implements ContextLoader<Map<String, Object>> {

    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, String> failWith = new ConcurrentHashMap<>();
    private final Map<String, Long> delayMs = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();
    private volatile Function<String, Map<String, Object>> extra = tenantId -> Map.of();

    @Override
    public Map<String, Object> load(String tenantId, SecretResolver secrets) {
        int n = calls.computeIfAbsent(tenantId, k -> new AtomicInteger()).incrementAndGet();
        CountDownLatch hold = holds.get(tenantId);
        if (hold != null) awaitQuietly(hold);
        Long delay = delayMs.get(tenantId);
        if (delay != null) sleep(delay);
        String failure = failWith.get(tenantId);
        if (failure != null) throw new ContextLoadFailed(tenantId, failure);
        Map<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("tenantId", tenantId);
        ctx.put("load", n);
        ctx.putAll(extra.apply(tenantId));
        return Map.copyOf(ctx);
    }

    public int calls(String tenantId) { return calls.getOrDefault(tenantId, new AtomicInteger()).get(); }

    /** Every load of this tenant fails with this reason until {@link #recover}. */
    public ScriptedLoader failing(String tenantId, String reason) { failWith.put(tenantId, reason); return this; }

    public ScriptedLoader recover(String tenantId) { failWith.remove(tenantId); return this; }

    public ScriptedLoader slow(String tenantId, long ms) { delayMs.put(tenantId, ms); return this; }

    /** The next loads of this tenant block until {@link #release}. */
    public CountDownLatch hold(String tenantId) { CountDownLatch l = new CountDownLatch(1); holds.put(tenantId, l); return l; }

    public void release(String tenantId) { CountDownLatch l = holds.remove(tenantId); if (l != null) l.countDown(); }

    public ScriptedLoader extra(Function<String, Map<String, Object>> extra) { this.extra = extra; return this; }

    private static void awaitQuietly(CountDownLatch l) {
        try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
