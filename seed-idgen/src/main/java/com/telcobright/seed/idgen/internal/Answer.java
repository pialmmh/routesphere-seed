package com.telcobright.seed.idgen.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.telcobright.seed.idgen.api.IdRefused;

/** One answer of a shard: its status and its JSON body (an empty object when it sent none). */
record Answer(int status, JsonNode body) {

    /** The {@code error} word {@link Shard} gives a body that is not JSON (a bare {@code 404 page not found}: a service without the v2 roads). */
    static final String NOT_JSON = "not JSON";

    boolean ok() { return status >= 200 && status < 300; }

    /** The service's {@code error} word, or empty. */
    String error() { return body.path("error").asText(""); }

    /**
     * The body of a successful answer. A shard that failed on its side or has no values left is skipped ({@link ShardDown}); a
     * request the service refused is refused everywhere ({@link IdRefused}) — the tenant refusals (400 {@code Tenant is required},
     * {@code Invalid tenant}, {@code Unknown tenant}) among them, and a service that has no {@code /api/v2/{tenant}/} roads at all.
     * {@code what} names the shard, the tenant, the entity and the road, so every refusal names them.
     */
    JsonNode bodyOrThrow(String what) {
        if (ok()) return body;
        if (status >= 500 || "Range exhausted".equals(error())) throw new ShardDown(what + ": HTTP " + status + " " + error() + " " + message());
        if (status == 404 && NOT_JSON.equals(error())) {
            throw new IdRefused(status, "No v2 road", what + " — the id service answered '" + message().strip()
                + "': it has no /api/v2/{tenant}/ roads; this client needs the tenant-first service (bucket-next tenant-first)");
        }
        throw new IdRefused(status, error(), what + " — " + message());
    }

    private String message() { return body.path("message").asText(""); }
}
