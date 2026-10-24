package com.telcobright.seed.context.spi;

/**
 * The product's side, P1-O: what a snapshot carries beside its context — built ONCE, in the same step that installs the snapshot (the first
 * load and every reload of that tenant), never by a reader. routesphere's trees index every key a session looks a partner up by (seed-tenant
 * hands the base's {@code RootTree} here): a reader takes {@code snapshot.index()} and asks a map, it never walks the context.
 *
 * <p>Called under the tenant's cell, with the version the snapshot will carry. A throw fails the load like a loader's throw: the last good
 * snapshot stays, the failure is recorded, the version is not taken.
 */
@FunctionalInterface
public interface SnapshotIndexer<T> {

    /** The index of {@code context}, about to be installed as {@code tenantId}'s snapshot {@code version}; null = none. */
    Object index(String tenantId, long version, T context);

    /** Nothing built: the snapshot carries no index (every product before P1-O). */
    static <T> SnapshotIndexer<T> none() { return (tenantId, version, context) -> null; }
}
