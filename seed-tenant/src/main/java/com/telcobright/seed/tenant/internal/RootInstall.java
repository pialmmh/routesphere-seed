package com.telcobright.seed.tenant.internal;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.context.api.Snapshot;
import com.telcobright.seed.context.spi.SnapshotIndexer;
import com.telcobright.seed.sessionflow.api.RootTreeInstaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The install step of seed-tenant's trees (P1-O, the architect's A1): seed-context's swap calls it with every loaded tree — the first load
 * and every reload of a served root — and the snapshot carries what it built ({@link InstalledRoot}); no session builds anything. It also
 * keeps the served roots by their code and their database name, so a root named by either is found by one get (A7), and counts the builds
 * ({@link #rootIndexBuilds()}: roots × loads).
 */
public final class RootInstall implements SnapshotIndexer<Tenant> {

    private static final Logger log = LoggerFactory.getLogger(RootInstall.class);

    private final RootTreeInstaller installer = new RootTreeInstaller();
    private final Map<String, String> servedIdByCode = new ConcurrentHashMap<>();
    private final Map<String, String> servedIdByRootDb = new ConcurrentHashMap<>();
    private final Map<String, InstalledRoot> installedLate = new ConcurrentHashMap<>();

    /** One loaded tree, installed: its index and its D5 map built now (the index says every key two tiers hold, once). */
    @Override
    public InstalledRoot index(String servedId, long version, Tenant tree) {
        InstalledRoot installed = new InstalledRoot(installer.install(servedId, version, tree), PartnerTiers.of(servedId, version, tree, NOPLogger.NOP_LOGGER));
        if (tree.getName() != null) servedIdByCode.put(tree.getName(), servedId);
        servedIdByRootDb.put(tree.getDbName(), servedId);
        return installed;
    }

    /**
     * A snapshot's installed root; a snapshot of contexts built without this step (the trees' public constructor over contexts of one's own)
     * is installed once per version, here, and said once.
     */
    public InstalledRoot of(Snapshot<Tenant> snapshot) {
        InstalledRoot installed = snapshot.index(InstalledRoot.class);
        return installed != null ? installed : installedLate.compute(snapshot.tenantId(), (id, last) -> last != null && last.tree().version() == snapshot.version()
            ? last : installLate(snapshot));
    }

    private InstalledRoot installLate(Snapshot<Tenant> snapshot) {
        log.warn("tenant {}: its contexts were built without the trees' install step (TenantTrees.builder() gives it): snapshot v{} is indexed at its"
            + " first ask instead of at its load", snapshot.tenantId(), snapshot.version());
        return index(snapshot.tenantId(), snapshot.version(), snapshot.context());
    }

    /** The served id of a root named by its code; null = none installed. */
    public String servedIdOfCode(String code) { return code == null ? null : servedIdByCode.get(code); }

    /** The served id of a root named by its database name; null = none installed. */
    public String servedIdOfRootDb(String rootDb) { return rootDb == null ? null : servedIdByRootDb.get(rootDb); }

    /** The root indexes built: one per root per load. */
    public long rootIndexBuilds() { return installer.rootIndexBuilds(); }
}
