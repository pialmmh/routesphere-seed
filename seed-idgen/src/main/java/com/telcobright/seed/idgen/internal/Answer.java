package com.telcobright.seed.idgen.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.telcobright.seed.idgen.api.IdRefused;

/** One answer of a shard: its status and its JSON body (an empty object when it sent none). */
record Answer(int status, JsonNode body) {

    boolean ok() { return status >= 200 && status < 300; }

    /** The service's {@code error} word, or empty. */
    String error() { return body.path("error").asText(""); }

    /**
     * The body of a successful answer. A shard that failed on its side or has no values left is skipped ({@link ShardDown}); a
     * request the service refused is refused everywhere ({@link IdRefused}).
     */
    JsonNode bodyOrThrow(String what) {
        if (ok()) return body;
        if (status >= 500 || "Range exhausted".equals(error())) throw new ShardDown(what + ": HTTP " + status + " " + error() + " " + message());
        throw new IdRefused(status, error(), message());
    }

    private String message() { return body.path("message").asText(""); }
}
