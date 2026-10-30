package com.telcobright.seed.idgen.internal;

/**
 * The id service's roads, tenant-first (bucket-next {@code tenant-first}, docs/api-reference.md): everything under {@code /api/} names
 * its tenant before anything else — {@code /api/v2/{tenant}/{road}/{entity}}. The ONE place the shape is written; the pre-tenant roads
 * ({@code /api/{road}/{entity}}) are never called by this client.
 */
final class Roads {

    private Roads() {}

    static String nextId(String tenant, String entity) { return v2(tenant, "next-id", entity); }

    static String nextBatch(String tenant, String entity) { return v2(tenant, "next-batch", entity); }

    static String status(String tenant, String entity) { return v2(tenant, "status", entity); }

    static String init(String tenant, String entity) { return v2(tenant, "init", entity); }

    static String reset(String tenant, String entity) { return v2(tenant, "reset", entity); }

    private static String v2(String tenant, String road, String entity) { return "/api/v2/" + tenant + "/" + road + "/" + entity; }
}
