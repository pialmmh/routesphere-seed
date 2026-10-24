package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The install step of a root's snapshot (P1-O, the architect's A1): the ONE place a {@link RootTree} is made, and so the one place its
 * {@link RootIndex} is built — eagerly, by the code that installs the snapshot (seed-context's swap through seed-tenant's trees, at the
 * first load and every reload; {@code TenantLookup.of} at its construction), never by a session. {@link #rootIndexBuilds()} counts the
 * builds: roots × loads, never sessions — the kit and the engine's stats read it.
 *
 * <p>A lookup of a lane's own that installs nothing keeps the old road through {@link #installOnce}: the first session of each new tree
 * object pays the build, every later one gets the same instance.
 */
public final class RootTreeInstaller {

    private final LongAdder rootIndexBuilds = new LongAdder();
    private final Map<String, RootTree> installedOnce = new ConcurrentHashMap<>();

    /** Install {@code tree} as {@code root}'s snapshot {@code version}: its index built now, once. */
    public RootTree install(String root, long version, Tenant tree) {
        RootTree installed = RootTree.of(root, version, tree);
        rootIndexBuilds.increment();
        return installed;
    }

    /**
     * The tree installed under {@code root} while its tree object and version stay the same; a new tree object (or version) is installed
     * once — two sessions asking at once wait for the one build. For a lookup that has no install step of its own.
     */
    public RootTree installOnce(String root, long version, Tenant tree) {
        return installedOnce.compute(root, (name, last) -> last != null && last.tree() == tree && last.version() == version ? last : install(name, version, tree));
    }

    /** The indexes built: one per root per load — never one per session. */
    public long rootIndexBuilds() { return rootIndexBuilds.sum(); }
}
