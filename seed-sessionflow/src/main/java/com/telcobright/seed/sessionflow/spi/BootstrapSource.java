package com.telcobright.seed.sessionflow.spi;

import com.telcobright.seed.sessionflow.api.LogText;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Where an application's whole config comes from (R8, the owner: "routesphere-seed/flow shall fetch all config through the prime-context
 * bootstrap url; its own yml config shall contain only a bootstrap url"): prime-context's {@code GET /api/v1/bootstrap/{tenant}/{app}}, the
 * token-gated JSON envelope (§C.1 — {@code tenant}, {@code app}, {@code env}, {@code config.routesphere}, {@code secrets}, {@code etag}).
 * The real source is HTTP ({@code BootstrapSources.http}: the token from the environment, {@code If-None-Match}/304); a test hands in a map
 * ({@code testkit.MapBootstrapSource}).
 */
public interface BootstrapSource {

    /** The envelope as prime-context serves it now (a 304 answers the last one). Throws {@link BootstrapUnavailable} when it cannot answer. */
    Map<String, Object> envelope();

    /** prime-context's own address ({@code scheme://host:port}): the roots' trees are read there ({@code get-specific-tenant-root}). */
    String origin();

    /** Where the envelope comes from, for a log line — never the token. */
    default String where() { return LogText.maskPassword(origin()); }

    /**
     * P1-O · The service's own token for prime-context's other roads (the roots' trees, {@code get-specific-tenant-root}): the SAME token
     * the envelope is asked with, read at every ask (a rotated token is taken); null = none. Never printed, never kept in a text.
     */
    default Supplier<String> bearer() { return () -> null; }

    /** prime-context did not answer, or answered with no envelope. */
    final class BootstrapUnavailable extends RuntimeException {
        public BootstrapUnavailable(String message) { super(message); }
        public BootstrapUnavailable(String message, Throwable cause) { super(message, cause); }
    }
}
