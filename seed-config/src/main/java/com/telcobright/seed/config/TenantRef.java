package com.telcobright.seed.config;

/**
 * One tenant enablement row, as declared in application.properties (or system
 * properties, which win):
 *
 * <pre>
 *   tenants[0].name=wifi_btcl
 *   tenants[0].enabled=true
 *   tenants[0].profile=dev
 *   active.tenant=wifi_btcl
 * </pre>
 */
public record TenantRef(String name, String profile, boolean enabled) {}
