package com.telcobright.seed.context.spi;

/**
 * The ONE topic naming function the product already has — tenant-kit's {@code TopicNames}
 * ({@code <base>_<tenantId>}, an installation alias map) on the wifi side, routesphere's
 * {@code TenantTopicResolver} on the telecom side. Injected, so the seed never imports a product and
 * never grows a second naming rule. The cache subscribes to the EXACT list of the active tenants'
 * topics — never a pattern.
 */
@FunctionalInterface
public interface TopicNaming {
    String topic(String base, String tenantId);

    /** The plain convention when a product has no naming function of its own yet. */
    static TopicNaming underscore() { return (base, tenantId) -> base + "_" + tenantId; }
}
