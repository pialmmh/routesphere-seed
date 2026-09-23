package com.telcobright.seed.context.internal;

import com.telcobright.seed.configclient.KafkaTopicsDoorbellSource;
import com.telcobright.seed.context.api.ContextStats;
import com.telcobright.seed.context.api.Snapshot;
import com.telcobright.seed.context.api.TenantContextUnavailable;
import com.telcobright.seed.context.api.TenantContexts;
import com.telcobright.seed.context.publishes.ContextEvent;
import com.telcobright.seed.context.publishes.ContextListener;
import com.telcobright.seed.context.spi.ContextLoader;
import com.telcobright.seed.context.spi.SecretResolver;
import com.telcobright.seed.context.spi.TenantDirectory;
import com.telcobright.seed.context.spi.TopicNaming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The map of cells, the directory refresh, the doorbell binding, the backstop. */
public final class TenantContextsImpl<T> implements TenantContexts<T> {

    private static final Logger log = LoggerFactory.getLogger(TenantContextsImpl.class);

    private final TenantDirectory directory;
    private final ContextLoader<T> loader;
    private final SecretResolver secrets;
    private final ContextListener listener;
    private final long debounceMs;
    private final Duration backstopEvery;
    private final Duration loadTimeout;
    private final Duration startTimeout;
    private final String kafkaBootstrap, topicBase, kafkaGroupId;
    private final TopicNaming naming;
    private final boolean kafkaFailFast;

    private final Map<String, TenantCell<T>> cells = new ConcurrentHashMap<>();
    private final Map<String, String> tenantByTopic = new ConcurrentHashMap<>();
    private KafkaTopicsDoorbellSource doorbell;
    private Backstop backstop;
    private volatile boolean started;

    public TenantContextsImpl(TenantDirectory directory, ContextLoader<T> loader, SecretResolver secrets,
                              ContextListener listener, long debounceMs, Duration backstopEvery, Duration loadTimeout,
                              Duration startTimeout, String kafkaBootstrap, String topicBase, TopicNaming naming,
                              String kafkaGroupId, boolean kafkaFailFast) {
        this.directory = directory;
        this.loader = loader;
        this.secrets = secrets;
        this.listener = listener;
        this.debounceMs = debounceMs;
        this.backstopEvery = backstopEvery;
        this.loadTimeout = loadTimeout;
        this.startTimeout = startTimeout;
        this.kafkaBootstrap = kafkaBootstrap;
        this.topicBase = topicBase;
        this.naming = naming;
        this.kafkaGroupId = kafkaGroupId;
        this.kafkaFailFast = kafkaFailFast;
    }

    // ── lifecycle ──────────────────────────────────────────────────────────

    @Override
    public synchronized TenantContexts<T> start() {
        if (started) return this;
        started = true;
        List<CompletableFuture<Snapshot<T>>> initial = syncWithDirectory("startup");
        awaitInitialLoads(initial);
        openDoorbell();
        backstop = new Backstop(backstopEvery, () -> cells.values().forEach(c -> c.ring("backstop")));
        log.info("tenant contexts up: {} tenant(s), {} serving, doorbell {}", cells.size(), servingCount(),
            doorbell == null ? "off" : "kafka " + topicBase);
        return this;
    }

    @Override
    public synchronized void close() {
        if (backstop != null) backstop.close();
        if (doorbell != null) doorbell.close();
        cells.values().forEach(TenantCell::close);
        cells.clear();
        tenantByTopic.clear();
    }

    // ── reads ──────────────────────────────────────────────────────────────

    @Override
    public Optional<Snapshot<T>> find(String tenantId) {
        TenantCell<T> cell = tenantId == null ? null : cells.get(tenantId);
        return cell == null ? Optional.empty() : Optional.ofNullable(cell.current());
    }

    @Override
    public Snapshot<T> require(String tenantId) {
        TenantCell<T> cell = tenantId == null ? null : cells.get(tenantId);
        if (cell == null) throw new TenantContextUnavailable(String.valueOf(tenantId), "not a tenant of this instance");
        Snapshot<T> s = cell.current();
        if (s == null) throw new TenantContextUnavailable(tenantId, "no successful load yet"
            + (cell.lastFailure() == null ? "" : " — last failure: " + cell.lastFailure()));
        return s;
    }

    @Override
    public Set<String> tenants() { return Set.copyOf(cells.keySet()); }

    @Override
    public ContextStats stats(String tenantId) {
        TenantCell<T> cell = cells.get(tenantId);
        if (cell == null) throw new TenantContextUnavailable(String.valueOf(tenantId), "not a tenant of this instance");
        return cell.stats();
    }

    @Override
    public Map<String, ContextStats> stats() {
        Map<String, ContextStats> out = new TreeMap<>();
        cells.forEach((id, cell) -> out.put(id, cell.stats()));
        return out;
    }

    // ── writes ─────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<Snapshot<T>> reload(String tenantId, String reason) {
        TenantCell<T> cell = cells.get(tenantId);
        if (cell == null) return CompletableFuture.failedFuture(new TenantContextUnavailable(String.valueOf(tenantId), "not a tenant of this instance"));
        return cell.reload(reason == null ? "reload" : reason);
    }

    @Override
    public void ring(String tenantId, String source) {
        TenantCell<T> cell = cells.get(tenantId);
        if (cell == null) { log.debug("doorbell for unknown tenant {} ignored ({})", tenantId, source); return; }
        cell.ring(source == null ? "manual" : source);
    }

    @Override
    public synchronized void refreshDirectory() { syncWithDirectory("directory"); }

    // ── steps ──────────────────────────────────────────────────────────────

    /** New tenants get a cell and a first load; tenants that left are dropped; the doorbell learns the new list. */
    private List<CompletableFuture<Snapshot<T>>> syncWithDirectory(String reason) {
        Set<String> active;
        try { active = Set.copyOf(directory.active()); }
        catch (RuntimeException e) { log.warn("tenant directory unavailable ({}) — keeping {} tenant(s)", e.toString(), cells.size()); return List.of(); }
        List<CompletableFuture<Snapshot<T>>> loads = new ArrayList<>();
        for (String id : active) {
            if (cells.containsKey(id)) continue;
            TenantCell<T> cell = new TenantCell<>(id, loader, secrets, listener, debounceMs, loadTimeout);
            cells.put(id, cell);
            listener.on(new ContextEvent.TenantAdded(id));
            loads.add(cell.reload(reason));
        }
        for (String id : List.copyOf(cells.keySet())) {
            if (active.contains(id)) continue;
            cells.remove(id).close();
            listener.on(new ContextEvent.TenantDropped(id));
        }
        resubscribe();
        return loads;
    }

    private void awaitInitialLoads(List<CompletableFuture<Snapshot<T>>> loads) {
        if (loads.isEmpty()) return;
        try {
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
                .exceptionally(t -> null)                       // one tenant's failure is that tenant's, not start's
                .get(startTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("start: {} initial load(s) still running after {} — serving what is there", loads.stream().filter(f -> !f.isDone()).count(), startTimeout);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private void openDoorbell() {
        if (kafkaBootstrap == null || topicBase == null) return;
        doorbell = new KafkaTopicsDoorbellSource(kafkaBootstrap, kafkaGroupId, kafkaFailFast, this::ringByTopic);
        doorbell.subscribe(topics());
        doorbell.start();
    }

    private void resubscribe() {
        tenantByTopic.clear();
        cells.keySet().forEach(id -> tenantByTopic.put(naming.topic(topicBase == null ? "config_event_loader" : topicBase, id), id));
        if (doorbell != null) doorbell.subscribe(topics());
    }

    private List<String> topics() { return new ArrayList<>(new TreeMap<>(tenantByTopic).keySet()); }

    private void ringByTopic(String topic) {
        String tenantId = tenantByTopic.get(topic);
        if (tenantId == null) { log.warn("doorbell on topic {} names no tenant of this instance — ignored, counted", topic); return; }
        ring(tenantId, "kafka");
    }

    private long servingCount() { return cells.values().stream().filter(c -> c.current() != null).count(); }

    /** For tests and the admin road: the topics this instance listens on, in order. */
    public Map<String, String> subscriptions() { return new LinkedHashMap<>(new TreeMap<>(tenantByTopic)); }
}
