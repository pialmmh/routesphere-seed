package com.telcobright.seed.sessionflow.api;

/**
 * P1-G · How a root that cannot be served yet is tried again ({@code config.routesphere.pending-root}): prime-context announces a root
 * ({@code tenant_created}, kind root) at its provisioning, BEFORE it serves it. The root is tried every {@code retry-sec} (the bootstrap
 * read again, the tree loaded) and given up after {@code give-up-min} — then a restart, or a new {@code tenant_created}, serves it.
 *
 * @param retrySec  seconds between two tries (default 30)
 * @param giveUpMin minutes a root stays pending before it is given up (default 120)
 */
public record PendingRootPace(long retrySec, long giveUpMin) {

    public static final PendingRootPace DEFAULT = new PendingRootPace(30, 120);

    public PendingRootPace {
        if (retrySec <= 0) throw new IllegalStateException("config.routesphere.pending-root.retry-sec must be above 0 s (was " + retrySec + "): a pending root would never be tried again");
        if (giveUpMin <= 0) throw new IllegalStateException("config.routesphere.pending-root.give-up-min must be above 0 min (was " + giveUpMin + ")");
    }
}
