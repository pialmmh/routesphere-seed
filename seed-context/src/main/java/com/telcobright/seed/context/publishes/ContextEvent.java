package com.telcobright.seed.context.publishes;

/** What the cache tells its host, one event per fact; a listener must be fast and must not throw. */
public sealed interface ContextEvent {
    String tenantId();

    /** A load succeeded and the snapshot was swapped. */
    record Reloaded(String tenantId, long version, long durationMs, String reason) implements ContextEvent {}

    /** A load failed; {@code servingVersion} is 0 when the tenant is absent, else the snapshot still serving. */
    record LoadFailed(String tenantId, long servingVersion, String reason, String cause) implements ContextEvent {}

    /** The directory listed a tenant the cache did not have. */
    record TenantAdded(String tenantId) implements ContextEvent {}

    /** The directory stopped listing a tenant: its snapshot is gone, its doorbell closed. */
    record TenantDropped(String tenantId) implements ContextEvent {}
}
