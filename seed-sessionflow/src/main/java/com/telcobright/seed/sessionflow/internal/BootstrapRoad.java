package com.telcobright.seed.sessionflow.internal;

import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.naming.api.Topics;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.api.TenantCreated;
import com.telcobright.seed.sessionflow.dependencies.KitMakers;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.TopicProvisioner;
import com.telcobright.seed.sessionflow.spi.BootstrapSource;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LiveTrees;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The kit's road to prime-context (R8): the bootstrap read, every served root's resources made — its tree, its doors, its ledger, its
 * CDR road ({@code cdr_<root>}), its journal — and kept, so that a root added live (R10) is served the same way. One per kit made from the
 * bootstrap; a kit built by hand has none.
 */
public final class BootstrapRoad implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRoad.class);
    static final String CDR = "cdr", DOORBELL = "config_event_loader";

    private final BootstrapSource source;
    private final KitMakers makers;
    private volatile Deployment deployment;
    private final LiveTrees trees;
    private final LiveRules rules;
    private final PerRootCdrSink cdr = new PerRootCdrSink();
    private final PerRootLedger ledger;
    private final TiersFirst tiersFirst;
    private final PerRootJournal journal = new PerRootJournal();
    private final Map<String, Producer<String, String>> producers = new ConcurrentHashMap<>();
    private final Map<String, Admin> admins = new ConcurrentHashMap<>();
    private volatile Function<String, List<String>> laneTopics = root -> List.of();
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final PendingRoots pending;
    private TenantCreatedListener listener;

    private BootstrapRoad(BootstrapSource source, KitMakers makers, Deployment deployment) {
        this.source = source;
        this.makers = makers;
        this.deployment = deployment;
        this.trees = makers.trees().make(deployment, source);
        this.rules = new LiveRules(deployment.rules());
        this.ledger = new PerRootLedger(this::rootOf);
        this.tiersFirst = new TiersFirst(ledger);
        trees.gateEachNewTree(tiersFirst);                                  // P1-G: a new tree's tiers into their root's ledger first
        this.pending = new PendingRoots(deployment.app(), deployment.pendingRoot(), makers.clock(), root -> tryToServe(root, "tried again, it was pending"));
    }

    /** The bootstrap read for {@code app}, every served root's resources made; refused in words when the envelope says too little. */
    public static BootstrapRoad open(BootstrapSource source, String app, KitMakers makers) {
        BootstrapRoad road = new BootstrapRoad(source, makers, Deployment.of(source.envelope(), app));
        road.deployment.roots().forEach(root -> road.makeTheRootsRoads(road.deployment, root));
        log.info("[{}] the bootstrap of {} ({}): {}", app, road.deployment.tenant(), source.where(), road.describe());
        return road;
    }

    /** The kit of this road: the trees, the doors, the ledger, the CDR road and the journal of every served root. */
    public RoutesphereKit kit() {
        return RoutesphereKit.builder().tenants(trees).resolver(rules).ledger(ledger).cdr(cdr).journal(journal)
            .zone(deployment.zone()).settings(deployment.settings()).clock(makers.clock()).bootstrap(this).build();
    }

    public Deployment deployment() { return deployment; }

    public LiveTrees trees() { return trees; }

    /** Every served root's resources, one line each — what the start says it got. */
    public String describe() {
        List<String> lines = new ArrayList<>();
        for (String root : deployment.roots()) lines.add(describe(deployment.root(root).orElseThrow()));
        return String.join("; ", lines);
    }

    // ── the topics of every served root, made at the start (R9) ──

    /**
     * The start (the engine calls it once, before the first session): every served root's topics made, then {@code tenant_created} listened
     * to on the platform's Kafka (its own consumer group: every process hears every event).
     */
    public void start(Function<String, List<String>> laneTopics) {
        this.laneTopics = laneTopics;
        deployment.roots().forEach(this::ensureTheTopicsOf);
        listener = new TenantCreatedListener(makers.kafka().consumer(deployment.platformKafka(), deployment.app() + "-tenant-created-" + UUID.randomUUID()),
            this::onTenantCreated, deployment.app()).start();
    }

    /** The topics of one root: {@code cdr_<root>} (the switch publishes), {@code config_event_loader_<root>} (the doorbell), the lane's own. */
    public List<String> topicsOf(String root) {
        List<String> topics = new ArrayList<>(List.of(Topics.platform(CDR, root), Topics.platform(DOORBELL, root)));
        List<String> lanes = laneTopics.apply(root);
        if (lanes != null) topics.addAll(lanes);
        return topics;
    }

    private void ensureTheTopicsOf(String root) {
        RootResources.KafkaRoad kafka = deployment.root(root).orElseThrow().kafka();
        TopicProvisioner.ensure(adminOf(kafka.bootstrap()), topicsOf(root), kafka.partitions(), kafka.replication(), kafka.bootstrap());
    }

    private Admin adminOf(String bootstrap) { return admins.computeIfAbsent(bootstrap, b -> makers.kafka().admin(b)); }

    // ── a tenant created live (R10) ──

    /**
     * prime-context created a tenant (§C.2): a new ROOT this application serves → the bootstrap read again, the root's tree, doors, ledger,
     * CDR road, journal and topics made, served from now on (no restart); a new reseller TIER of a served root → that root's tree reloaded.
     * The same event twice is one; an event of another application, or a tier of a root not served, is nothing. P1-G: a root that cannot be
     * served yet — not listed for this application yet, or its tree not served by prime-context yet (the message comes BEFORE the root is
     * served) — is never half-served: it goes PENDING and is tried again ({@link PendingRoots}); a later word tries it again at once.
     */
    public void onTenantCreated(TenantCreated event) {
        if (!event.names(deployment.app()) || !seen.add(event.identity())) return;
        if (event.isRoot()) serveTheNewRoot(event);
        else reloadTheRootOf(event);
    }

    private synchronized void serveTheNewRoot(TenantCreated event) {
        String notYet = tryToServe(event.root(), "tenant_created at " + event.at());
        if (notYet == null) return;
        seen.remove(event.identity());                                    // a later word tries it again at once
        pending.pend(event.root(), notYet);
    }

    /**
     * One try to serve a root (a word, or the pending roots' timer): the bootstrap read again, the root's tree loaded, then its roads, its
     * doors and its topics. Null = served now (or before); otherwise why not yet, in words — and nothing of the root is served.
     */
    private synchronized String tryToServe(String root, String why) {
        if (deployment.roots().contains(root)) { pending.served(root); return null; }
        Deployment next;
        try { next = Deployment.of(source.envelope(), deployment.app()); }
        catch (RuntimeException e) { return "the bootstrap could not be read again: " + e.getMessage(); }
        if (!next.roots().contains(root)) return "not listed for " + next.app() + " yet (its roots " + next.roots() + ")";
        try { trees.serve(root); }
        catch (RuntimeException e) { return "prime-context does not serve its tree yet: " + e.getMessage(); }
        serveItsRoads(next, root, why);
        return null;
    }

    private void serveItsRoads(Deployment next, String root, String why) {
        makeTheRootsRoads(next, root);
        deployment = next;
        rules.replace(next.rules());
        ensureTheTopicsOf(root);
        String line = "[" + next.app() + "] the root " + root + " is served from now on (" + why + "): " + describe(next.root(root).orElseThrow());
        log.info(line);
        if (pending.roots().contains(root)) pending.note(line);
        pending.served(root);
    }

    /** The roots announced that cannot be served yet, tried again (P1-G). */
    public Set<String> pendingRoots() { return pending.roots(); }

    /** What the road said of its pending roots — once each: pending (why), served, given up (P1-G; the operations' view). */
    public List<String> said() { return pending.said(); }

    /** A new tier of a served root: its root's ledger loads the tier first, then the tree shows it to the next session (item 13). */
    private void reloadTheRootOf(TenantCreated event) {
        if (!deployment.roots().contains(event.root())) return;
        tiersFirst.hand(rootDbOf(event.root()), event.tierDb());
        trees.reload(event.root());
        log.info("[{}] the tier {} under {} is in the ledger and the tree from now on (its root reloaded)", deployment.app(), event.tierDb(), event.root());
    }

    /** The engine's close: the listener, the admins, every root's CDR road, journal and ledger, the producers, the trees. */
    @Override
    public void close() {
        pending.close();
        if (listener != null) listener.close();
        admins.values().forEach(Admin::close);
        ledger.close();
        cdr.close();
        journal.close();
        producers.values().forEach(Producer::close);
        trees.close();
    }

    // ── one root's roads ──

    private void makeTheRootsRoads(Deployment d, String root) {
        RootResources r = d.root(root).orElseThrow();
        String rootDb = rootDbOf(root);
        LedgerMaker.Context context = new LedgerMaker.Context(d, trees, makers.clock(), makers.environment());
        RootTree builtWith = trees.snapshot(root).orElse(null);
        ledger.add(rootDb, makers.ledger(r.ledger().kind()).make(r, context));
        if (builtWith != null) tiersFirst.builtOver(rootDb, builtWith.tree());
        cdr.add(rootDb, new KafkaCdrSink(producerOf(r.kafka().bootstrap()), Topics.platform(CDR, rootDb), null));
        journal.add(rootDb, makers.journalOf(r.dir()));
    }

    private Producer<String, String> producerOf(String bootstrap) {
        return producers.computeIfAbsent(bootstrap, b -> makers.kafka().producer(b, deployment.app() + "-cdr"));
    }

    /** A root's database name as its tree says it (the root's name, R9). */
    private String rootDbOf(String root) { return trees.snapshot(root).map(RootTree::rootDbName).orElse(root); }

    /** The root (database name) of a tier's tenant: its chain's last; a tenant with no chain — the served root whose tree holds it. */
    private String rootOf(Tenant tenant) {
        if (tenant.getAncestorChain() != null && !tenant.getAncestorChain().isEmpty()) return PerRootLedger.rootByTheChain(tenant);
        for (String root : deployment.roots()) {
            if (trees.tenantByDbName(rootDbOf(root), tenant.getDbName()).isPresent()) return rootDbOf(root);
        }
        return tenant.getDbName();
    }

    private String describe(RootResources r) {
        String tree = trees.snapshot(r.root()).map(t -> "tree v" + t.version()).orElse("NO TREE");
        String db = r.switchDb() == null ? "" : " on " + r.switchDb().url() + " schema " + r.switchDb().schema();
        return r.root() + ": " + tree + ", doors " + deployment.rulesOf(r.root()) + ", ledger " + r.ledger().kind() + db + ", CDR " + Topics.platform(CDR, r.root())
            + " on " + r.kafka().bootstrap() + ", redis " + r.redisUrl() + ", dir " + r.dir();
    }
}
