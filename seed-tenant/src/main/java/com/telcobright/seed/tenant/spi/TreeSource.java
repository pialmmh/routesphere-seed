package com.telcobright.seed.tenant.spi;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;

/**
 * Where a served tenant's tree comes from: the whole hierarchy of one root — the root tenant and every nested {@code res_<id>} tier,
 * each with its own {@code DynamicContext} — as prime-context serves it. The real source is the HTTP road
 * ({@code internal.PrimeContextTreeSource}); a test hands in trees it built. The tree comes back AS FETCHED: the module makes it
 * walkable (the index, the chains) in one place after the fetch, so a source never has to.
 *
 * <p>A source that cannot answer throws {@link TreeUnavailable}; the cache keeps the tenant's last good tree and says so.
 */
@FunctionalInterface
public interface TreeSource {

    Tenant fetch(String tenantName);

    final class TreeUnavailable extends RuntimeException {
        public TreeUnavailable(String message) { super(message); }
        public TreeUnavailable(String message, Throwable cause) { super(message, cause); }
    }
}
