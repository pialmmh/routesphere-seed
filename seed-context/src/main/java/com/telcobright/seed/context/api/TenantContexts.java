package com.telcobright.seed.context.api;

import com.telcobright.seed.context.dependencies.TenantContextsBuilder;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The app cache of a multi-tenant product: one immutable {@code T} per tenant, built by the product's
 * loader, swapped whole, versioned, and refreshed per tenant when the config doorbell rings — the
 * routesphere DynamicContext shape with sibling-level tenancy. A tenant the directory does not list,
 * or whose every load failed, is ABSENT: {@link #require} throws, it never answers with a sibling's
 * context or a default.
 *
 * <pre>
 *   TenantContexts&lt;PayContext&gt; contexts = TenantContexts.&lt;PayContext&gt;builder()
 *       .directory(registry::activeTenants)
 *       .loader(new PayContextLoader(...))
 *       .secrets(EnvSecrets.fromProcess())
 *       .doorbell("10.10.188.2:9092", "config_event_loader", naming, "pay-sphere-context")
 *       .build().start();
 *   PayContext ctx = contexts.require(tenantId).context();     // at admission, then carried on the session
 * </pre>
 */
public interface TenantContexts<T> extends AutoCloseable {

    static <T> TenantContextsBuilder<T> builder() { return new TenantContextsBuilder<>(); }

    /** Load every active tenant (in parallel, failures isolated), then open the doorbell and the backstop. */
    TenantContexts<T> start();

    /** The tenant's current snapshot; empty for an unknown tenant or one with no successful load yet. */
    Optional<Snapshot<T>> find(String tenantId);

    /** The tenant's current snapshot, or {@link TenantContextUnavailable} — never a default. */
    Snapshot<T> require(String tenantId);

    /** The tenants the cache knows (listed by the directory at the last refresh), serving or not. */
    Set<String> tenants();

    /**
     * Reload one tenant now (an admin road, a product's own "I know it changed"). Single-flight: a reload
     * during a load runs exactly once more after it. The future carries the snapshot of the load that
     * ran for this request, or fails with the load's cause.
     */
    CompletableFuture<Snapshot<T>> reload(String tenantId, String reason);

    /** A doorbell ring from outside (tests, a Redis leg the product wires itself): debounced like Kafka's. */
    void ring(String tenantId, String source);

    /** Re-read the directory: new tenants are loaded, tenants that left are dropped, the doorbell re-subscribes. */
    void refreshDirectory();

    ContextStats stats(String tenantId);

    Map<String, ContextStats> stats();

    @Override
    void close();
}
