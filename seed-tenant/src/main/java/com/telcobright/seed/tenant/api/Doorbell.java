package com.telcobright.seed.tenant.api;

import com.telcobright.seed.tenant.internal.KafkaDoorbell;

/**
 * The doorbell a product rings after ITS OWN write to a tenant's configuration (one writer holding the context): the local cache
 * reloads at once, and ONE record goes on {@code config_event_loader_<tenant>} so every other instance reloads that tenant too.
 * The record's content is {@code {tenantId, source, what, at}}; every consumer ignores it — the TOPIC names the tenant.
 * Without a broker only the local ring happens, said once at start.
 */
public interface Doorbell extends AutoCloseable {

    /** @return true when the ring left this process (a broker took it); false when only this instance reloaded. */
    boolean ring(String tenantId, String what);

    @Override void close();

    /** The Kafka doorbell of a service; a blank bootstrap = local rings only. */
    static Doorbell kafka(TenantTrees trees, String bootstrap, String service) { return KafkaDoorbell.of(trees, bootstrap, service); }

    static Doorbell localOnly(TenantTrees trees) { return KafkaDoorbell.of(trees, null, "local"); }
}
