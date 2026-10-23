package com.telcobright.seed.sessionflow.spi;

import com.telcobright.seed.sessionflow.api.Deployment;

/**
 * Makes the served roots' trees of a kit made from the bootstrap. seed-tenant answers with prime-context's tree road
 * ({@code get-specific-tenant-root} at the source's origin) and the doorbell; it is found on the classpath ({@code java.util.ServiceLoader}).
 */
public interface TreesMaker {

    /** The trees of every root the deployment serves, each loaded before this answers; a root that cannot be loaded is refused in words. */
    LiveTrees make(Deployment deployment, BootstrapSource source);
}
