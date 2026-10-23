package com.telcobright.seed.sessionflow.spi;

import java.util.Set;

/**
 * The served roots' trees of a kit made from the bootstrap: the base's {@link TenantLookup} (one snapshot per root) that can serve a root
 * added LIVE (R10: prime-context provisioned it, the {@code tenant_created} message named it) and reload one root's tree (a new reseller
 * tier — the doorbell's own road). seed-tenant's prime-context trees are the real ones; a test hands in its own.
 */
public interface LiveTrees extends TenantLookup, AutoCloseable {

    /** The roots served now. */
    Set<String> roots();

    /** Serve one more root: its tree is loaded now (a root that cannot be loaded is refused in words). Serving a served root is nothing. */
    void serve(String root);

    /** Load one served root's tree again (a tier was added under it): the next session takes the new snapshot. */
    void reload(String root);

    @Override default void close() { }
}
