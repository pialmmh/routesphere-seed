package com.telcobright.seed.context.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One tenant's context as loaded at one moment. {@code version} is monotonic per tenant and is what a
 * session carries (with the tenant id) into its own persisted context, so a resumed session can tell
 * that the configuration moved under it. {@code context} is the product's own immutable type.
 */
public record Snapshot<T>(String tenantId, long version, Instant loadedAt, T context) {
    public Snapshot {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(loadedAt, "loadedAt");
        Objects.requireNonNull(context, "context");
        if (version < 1) throw new IllegalArgumentException("version starts at 1");
    }
}
