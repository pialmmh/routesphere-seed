package com.telcobright.seed.context.api;

/** {@link TenantContexts#require} for a tenant that is unknown, or that has no successful load yet. */
public final class TenantContextUnavailable extends RuntimeException {
    private final String tenantId;

    public TenantContextUnavailable(String tenantId, String reason) {
        super("no context for tenant '" + tenantId + "': " + reason);
        this.tenantId = tenantId;
    }

    public String tenantId() { return tenantId; }
}
