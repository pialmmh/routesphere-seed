package com.telcobright.seed.tenant.api;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.context.api.Snapshot;
import com.telcobright.seed.context.api.TenantContexts;
import com.telcobright.seed.sessionflow.api.EntryPartner;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.tenant.dependencies.TenantTreesBuilder;
import com.telcobright.seed.tenant.internal.PartnerTiers;
import com.telcobright.seed.tenant.internal.RootInstall;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The tenant trees a product walks — routesphere's shape on the seed's cache (the owner, 2026-10-09: "each sibling-level tenant
 * will have own dynamic context in a hashmap; the nested multi-level resellers will have own dynamicContext, realized by the
 * hierarchy; loads config dynamically through prime-context, senses a db change through the Kafka topic notification"):
 *
 * <ul>
 *   <li><b>the map</b> — {@code TenantContexts<Tenant>}: one immutable {@link Tenant} tree per served tenant (the root; every nested
 *       {@code res_<id>} tier a child with its OWN {@code DynamicContext}), swapped whole, versioned — routesphere-core's
 *       {@code TenantHierarchyInitializer.tenantConfigs} as the seed extracted it;</li>
 *   <li><b>the directory</b> — the tenants this process serves, never a guess;</li>
 *   <li><b>the loader</b> — a {@link com.telcobright.seed.tenant.spi.TreeSource} (prime-context's {@code get-specific-tenant-root});</li>
 *   <li><b>the notification</b> — the Kafka doorbell on {@code config_event_loader_<tenant>}: a record's content is ignored, its topic
 *       names the tenant to reload (debounced); a backstop; without a broker, {@link #ring} and {@link #reload} only.</li>
 * </ul>
 *
 * It is the session pipeline's {@link TenantLookup}: every question is asked INSIDE ONE served tenant's tree (a partner id is unique
 * inside one tree only — every operator's root starts at partner 1) and answered from the CURRENT snapshot, so a session admitted after
 * a reload walks the new tree and one admitted before keeps the tiers it was given. A tenant the directory does not list, or whose every
 * load failed, is absent — never a sibling's tree, never a default. An id two tiers of one tree hold names nobody (D5,
 * {@link PartnerTiers}).
 *
 * <p><b>Built once per snapshot, never per session</b> (P1-O): seed-context's swap installs every loaded tree through {@link RootInstall} —
 * the base's {@link RootTree} with its index and the D5 map, carried ON the snapshot — so {@link #snapshot} hands every session the same
 * instance, and a served root named by its code or its database name is found by one get, never by a walk of the served roots.
 */
public final class TenantTrees implements TenantLookup, AutoCloseable {

    private final TenantContexts<Tenant> contexts;
    private final RootInstall install;

    /** Trees over contexts built by {@link TenantTreesBuilder} with {@code install} as their indexer: every snapshot comes installed. */
    public TenantTrees(TenantContexts<Tenant> contexts, RootInstall install) {
        this.contexts = contexts;
        this.install = install;
    }

    /**
     * Trees over contexts of one's own, built without the install step: each snapshot is installed at its first ask (said once). Use
     * {@link #builder()}: it installs every snapshot at its load.
     */
    public TenantTrees(TenantContexts<Tenant> contexts) { this(contexts, new RootInstall()); }

    public static TenantTreesBuilder builder() { return new TenantTreesBuilder(); }

    /** Load every served tenant, open the doorbell and the backstop. */
    public TenantTrees start() { contexts.start(); return this; }

    @Override public void close() { contexts.close(); }

    /** L2b item 3 · Every doorbell message's words, by root (its tenant id), as they arrive. */
    public void onDoorbellWords(java.util.function.BiConsumer<String, String> listener) { contexts.onDoorbellWords(listener); }

    // ── the map ──

    /** The tenant's current tree with its version; empty for a tenant not served or never loaded. */
    public Optional<Snapshot<Tenant>> find(String tenantId) { return contexts.find(tenantId); }

    public Set<String> tenants() { return contexts.tenants(); }

    /** A notification from outside the broker (a product's own write, a test): debounced like the Kafka doorbell. */
    public void ring(String tenantId, String source) { contexts.ring(tenantId, source); }

    /** Reload one tenant now; the future carries the snapshot of the load that ran, or its failure. */
    public CompletableFuture<Snapshot<Tenant>> reload(String tenantId, String reason) { return contexts.reload(tenantId, reason); }

    /** The directory changed: new tenants are loaded, gone ones dropped, the doorbell re-subscribes. */
    public void refreshDirectory() { contexts.refreshDirectory(); }

    public TenantContexts<Tenant> contexts() { return contexts; }

    // ── a served tenant's own tree: what a product's flow asks ──

    /**
     * The partner's entry INSIDE THE SERVED TENANT's tree: the tier that holds it, from that snapshot's index (built at its install, never a
     * walk per session), and the partner row of that tier. Null = no such tenant, or its tree holds no such partner in exactly one tier: for
     * this session the partner does not exist.
     */
    public EntryPartner entryIn(String servedTenant, int partnerId) {
        return find(servedTenant).map(snap -> install.of(snap).tree().entryOf(partnerId)).orElse(null);
    }

    /** The tiers that hold the id when MORE THAN ONE tier of the served tenant's tree does (schema order): such an id names nobody. */
    public List<String> tiersHoldingTwice(String servedTenant, int partnerId) {
        return find(servedTenant).map(snap -> install.of(snap).partnerTiers().heldByTwo().getOrDefault(partnerId, List.of())).orElse(List.of());
    }

    /** How many partner maps were built: one per loaded snapshot (its install), none on any ask (a test reads it). */
    public long walks() { return install.rootIndexBuilds(); }

    /** P1-O · The root indexes built: one per served root per load — never one per session. */
    @Override
    public long rootIndexBuilds() { return install.rootIndexBuilds(); }

    // ── TenantLookup: the base's questions, each inside the tree of the root it names ──

    @Override
    public Optional<Tenant> root(String rootDbName) {
        return servingTheTreeOf(rootDbName).flatMap(this::find).map(Snapshot::context);
    }

    @Override
    public Optional<Tenant> tenantOfPartner(String rootDbName, int partnerId) {
        return servingTheTreeOf(rootDbName).map(id -> entryIn(id, partnerId)).map(EntryPartner::tenant);
    }

    /**
     * The served root whose tenant CODE ({@code Tenant.name}) is {@code code} — one get on the codes the installs saw, no tree walked; two
     * served ids serving ONE tree answer the same root. The base's identification rules name a tenant by code and never see a schema name;
     * the facade makes every served root's name its code (ARCH-0077-E).
     */
    @Override
    public Optional<Tenant> rootOfCode(String code) {
        return Optional.ofNullable(install.servedIdOfCode(code)).flatMap(this::find).map(Snapshot::context)
            .filter(root -> code.equals(root.getName()));
    }

    /**
     * §D · The served root's CURRENT snapshot with its own version — what a session of the base takes at its start and keeps to its end:
     * the instance installed at its load (P1-O), the same for every session of that snapshot. The doorbell ({@code config_event_loader_<root>})
     * reloads THAT root only: the next session takes the new version, a running one finishes on the tree it started with (the snapshot is
     * immutable, swapped whole). The root is the served id (the tenant code the identification rule names), else a served root's code or
     * database name. Empty = not served, or never loaded.
     */
    @Override
    public Optional<RootTree> snapshot(String root) {
        if (root == null) return Optional.empty();
        return servedSnapshotNamed(root).map(snap -> install.of(snap).tree());
    }

    @Override
    public Optional<Tenant> tenantByDbName(String rootDbName, String dbName) {
        if (dbName == null) return Optional.empty();
        return root(rootDbName).map(tree -> tree.findTenantByDbName(dbName));
    }

    /** The snapshot of the root named by its served id, else by its code, else by its database name — gets only. */
    private Optional<Snapshot<Tenant>> servedSnapshotNamed(String root) {
        Optional<Snapshot<Tenant>> own = find(root);
        if (own.isPresent()) return own;
        Optional<Snapshot<Tenant>> byCode = Optional.ofNullable(install.servedIdOfCode(root)).flatMap(this::find).filter(s -> root.equals(s.context().getName()));
        if (byCode.isPresent()) return byCode;
        return Optional.ofNullable(install.servedIdOfRootDb(root)).flatMap(this::find).filter(s -> root.equals(s.context().getDbName()));
    }

    /**
     * The served tenant whose tree has that root: the served id itself when it names the root (the common case), else the served tenant
     * whose root's database name is it (two served tenants may serve ONE tree: the last installed answers — it is the same tree). Gets only.
     */
    private Optional<String> servingTheTreeOf(String rootDbName) {
        if (rootDbName == null) return Optional.empty();
        Optional<Snapshot<Tenant>> own = find(rootDbName);
        if (own.isPresent() && rootDbName.equals(own.get().context().getDbName())) return Optional.of(rootDbName);
        String id = install.servedIdOfRootDb(rootDbName);
        return Optional.ofNullable(id).filter(served -> find(served).filter(s -> rootDbName.equals(s.context().getDbName())).isPresent());
    }
}
