package com.telcobright.seed.tenant.api;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.context.api.Snapshot;
import com.telcobright.seed.context.api.TenantContexts;
import com.telcobright.seed.sessionflow.api.EntryPartner;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.tenant.dependencies.TenantTreesBuilder;
import com.telcobright.seed.tenant.internal.PartnerTiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 * {@link PartnerTiers}): one partner map per snapshot version, built once per reload.
 */
public final class TenantTrees implements TenantLookup, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TenantTrees.class);

    private final TenantContexts<Tenant> contexts;
    private final Map<String, PartnerTiers> partnerTiers = new ConcurrentHashMap<>();
    private final AtomicLong walks = new AtomicLong();

    public TenantTrees(TenantContexts<Tenant> contexts) { this.contexts = contexts; }

    public static TenantTreesBuilder builder() { return new TenantTreesBuilder(); }

    /** Load every served tenant, open the doorbell and the backstop. */
    public TenantTrees start() { contexts.start(); return this; }

    @Override public void close() { contexts.close(); }

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
     * The partner's entry INSIDE THE SERVED TENANT's tree: the tier that holds it, from that snapshot's partner map (built once per
     * snapshot, never a walk per session), and the partner row of that tier. Null = no such tenant, or its tree holds no such partner
     * in exactly one tier: for this session the partner does not exist.
     */
    public EntryPartner entryIn(String servedTenant, int partnerId) {
        Optional<Snapshot<Tenant>> snap = find(servedTenant);
        if (snap.isEmpty()) return null;
        Tenant tier = partnerTiersOf(servedTenant, snap.get()).byPartner().get(partnerId);
        Partner partner = tier == null || tier.getContext() == null || tier.getContext().getPartners() == null ? null : tier.getContext().getPartners().get(partnerId);
        return partner == null ? null : new EntryPartner(tier, partner);
    }

    /** The tiers that hold the id when MORE THAN ONE tier of the served tenant's tree does (schema order): such an id names nobody. */
    public List<String> tiersHoldingTwice(String servedTenant, int partnerId) {
        Optional<Snapshot<Tenant>> snap = find(servedTenant);
        if (snap.isEmpty()) return List.of();
        return partnerTiersOf(servedTenant, snap.get()).heldByTwo().getOrDefault(partnerId, List.of());
    }

    private PartnerTiers partnerTiersOf(String tenantId, Snapshot<Tenant> snap) {
        PartnerTiers known = partnerTiers.get(tenantId);
        if (known != null && known.version() == snap.version()) return known;
        walks.incrementAndGet();
        PartnerTiers fresh = PartnerTiers.of(tenantId, snap.version(), snap.context(), log);
        partnerTiers.put(tenantId, fresh);
        return fresh;
    }

    /** How many partner maps were built: one per loaded snapshot, none on a second ask (a test reads it). */
    public long walks() { return walks.get(); }

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
     * The served root whose tenant CODE ({@code Tenant.name}) is {@code code} — a handful of served roots compared by name, no tree walked;
     * two served ids serving ONE tree answer the same root. The base's identification rules name a tenant by code and never see a schema
     * name; the facade makes every served root's name its code (ARCH-0077-E).
     */
    @Override
    public Optional<Tenant> rootOfCode(String code) {
        if (code == null) return Optional.empty();
        return contexts.tenants().stream().map(this::find).flatMap(Optional::stream).map(Snapshot::context)
                .filter(root -> code.equals(root.getName())).findFirst();
    }

    @Override
    public Optional<Tenant> tenantByDbName(String rootDbName, String dbName) {
        if (dbName == null) return Optional.empty();
        return root(rootDbName).map(tree -> tree.findTenantByDbName(dbName));
    }

    /**
     * The served tenant whose tree has that root: the served id itself when it names the root (the common case), else the served tenant
     * whose root's database name is it (two served tenants may serve ONE tree: the first answers — it is the same tree). A handful of
     * root names compared; no tree walked.
     */
    private Optional<String> servingTheTreeOf(String rootDbName) {
        if (rootDbName == null) return Optional.empty();
        Optional<Snapshot<Tenant>> own = find(rootDbName);
        if (own.isPresent() && rootDbName.equals(own.get().context().getDbName())) return Optional.of(rootDbName);
        for (String id : contexts.tenants()) {
            Optional<Snapshot<Tenant>> s = find(id);
            if (s.isPresent() && rootDbName.equals(s.get().context().getDbName())) return Optional.of(id);
        }
        return Optional.empty();
    }
}
