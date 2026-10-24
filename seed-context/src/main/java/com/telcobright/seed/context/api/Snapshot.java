package com.telcobright.seed.context.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One tenant's context as loaded at one moment. {@code version} is monotonic per tenant and is what a
 * session carries (with the tenant id) into its own persisted context, so a resumed session can tell
 * that the configuration moved under it. {@code context} is the product's own immutable type.
 *
 * <p>P1-O · {@code index} is what the product's {@link com.telcobright.seed.context.spi.SnapshotIndexer} built from the context in the
 * same step that installed it (routesphere's trees: the root's lookups) — null when the product builds none. Built once per load, read by
 * every session of this snapshot.
 */
public record Snapshot<T>(String tenantId, long version, Instant loadedAt, T context, Object index) {
    public Snapshot {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(loadedAt, "loadedAt");
        Objects.requireNonNull(context, "context");
        if (version < 1) throw new IllegalArgumentException("version starts at 1");
    }

    /** The four words before P1-O, kept: a snapshot with no index. */
    public Snapshot(String tenantId, long version, Instant loadedAt, T context) { this(tenantId, version, loadedAt, context, null); }

    /** The index as the product's own type; null = none, or not of that type. */
    public <I> I index(Class<I> type) { return type.isInstance(index) ? type.cast(index) : null; }
}
