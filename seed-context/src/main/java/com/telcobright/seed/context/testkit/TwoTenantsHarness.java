package com.telcobright.seed.context.testkit;

import com.telcobright.seed.context.api.Snapshot;
import com.telcobright.seed.context.api.TenantContexts;
import com.telcobright.seed.context.dependencies.EnvSecrets;
import com.telcobright.seed.context.publishes.ContextEvent;
import com.telcobright.seed.context.publishes.ContextListener;
import com.telcobright.seed.context.spi.ContextLoader;
import com.telcobright.seed.context.spi.TenantDirectory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * The two-tenant test bed every multi-tenant product must have: tenants {@code a} and {@code b} in an
 * in-memory directory (mutable — a tenant can join or leave mid-test), an in-memory environment for the
 * {@code env:} pointers, the events recorded, and a manual ring. A product plugs its own loader in; the
 * {@link ScriptedLoader} serves for the kit's own tests.
 *
 * <p>The isolation rule a product's test must prove with it: whatever happens to {@code a} — a failed
 * load, a storm of rings, a dropped tenant — {@code b} keeps serving its own context, and no id, key
 * or index of {@code a} is ever answered from {@code b}.
 */
public final class TwoTenantsHarness<T> implements AutoCloseable {

    public static final String A = "a";
    public static final String B = "b";

    public final Set<String> active = ConcurrentHashMap.newKeySet();
    public final Map<String, String> environment = new ConcurrentHashMap<>();
    public final List<ContextEvent> events = new CopyOnWriteArrayList<>();
    public final TenantContexts<T> contexts;

    public TwoTenantsHarness(ContextLoader<T> loader, long debounceMs) {
        active.addAll(List.of(A, B));
        TenantDirectory directory = () -> Set.copyOf(active);
        ContextListener listener = events::add;
        contexts = TenantContexts.<T>builder()
            .directory(directory)
            .loader(loader)
            .secrets(EnvSecrets.of(environment))
            .listener(listener)
            .debounceMs(debounceMs)
            .backstop(Duration.ZERO)
            .loadTimeout(Duration.ofSeconds(5))
            .startTimeout(Duration.ofSeconds(5))
            .build();
    }

    public TwoTenantsHarness<T> start() { contexts.start(); return this; }

    public Optional<Snapshot<T>> a() { return contexts.find(A); }

    public Optional<Snapshot<T>> b() { return contexts.find(B); }

    /** Spin until the condition holds; false after the timeout. */
    public static boolean awaitUntil(BooleanSupplier condition, long timeoutMs) {
        long until = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < until) {
            if (condition.getAsBoolean()) return true;
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return condition.getAsBoolean();
    }

    public boolean awaitVersion(String tenantId, long version, long timeoutMs) {
        return awaitUntil(() -> contexts.find(tenantId).map(s -> s.version() >= version).orElse(false), timeoutMs);
    }

    public long count(Class<? extends ContextEvent> kind, String tenantId) {
        return events.stream().filter(kind::isInstance).filter(e -> e.tenantId().equals(tenantId)).count();
    }

    @Override
    public void close() { contexts.close(); }
}
