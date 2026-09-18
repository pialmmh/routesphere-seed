package com.telcobright.seed.routing.store;

import com.telcobright.seed.routing.policy.CompiledPolicy;
import com.telcobright.seed.routing.policy.PolicyTypes;
import com.telcobright.seed.routing.policy.RoutingPolicy;
import com.telcobright.seed.routing.spi.PolicyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The policies a switch routes with: every stored policy, COMPILED, in one immutable snapshot that the hot path
 * reads with a single map lookup. The store is read at start, when the config doorbell rings
 * ({@link #reload()}) and on a slow poll of the store's fingerprint — never per request.
 *
 * <p>Two safety rules. A policy whose new version does not compile KEEPS ITS LAST GOOD VERSION in service (and is
 * listed in {@link #problems()}): one bad document never takes routing down. A store that cannot be read leaves
 * the snapshot as it is, and says so with the cause.
 */
public final class PolicyCatalog implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PolicyCatalog.class);

    /** What one load did to one policy — for the journal and the admin view. */
    public record Change(String key, String what, int version, String detail) {}

    /** A stored policy that is NOT in service as stored (it does not compile). */
    public record Problem(String key, int version, String error, Instant seenAt) {}

    public record Snapshot(Map<String, CompiledPolicy> policies, List<Problem> problems, String fingerprint, Instant loadedAt) {}

    private final PolicyStore store;
    private final PolicyTypes types;
    private final List<Consumer<Change>> listeners = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot = new Snapshot(Map.of(), List.of(), "", Instant.EPOCH);
    private ScheduledExecutorService poller;

    public PolicyCatalog(PolicyStore store, PolicyTypes types) {
        this.store = store;
        this.types = types;
    }

    /** Be told what each load changed (loaded / replaced / removed / kept-last-good). */
    public PolicyCatalog onChange(Consumer<Change> listener) {
        listeners.add(listener);
        return this;
    }

    /**
     * The first load — a store that cannot be read fails the start, loudly — then the slow poll.
     *
     * @param pollSeconds 0 = no poll (the doorbell only)
     */
    public PolicyCatalog start(long pollSeconds) {
        load(true);
        if (pollSeconds > 0) {
            poller = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "routing-policy-poll");
                t.setDaemon(true);
                return t;
            });
            poller.scheduleWithFixedDelay(this::reloadIfChanged, pollSeconds, pollSeconds, TimeUnit.SECONDS);
        }
        return this;
    }

    /** The compiled policy, or null when the store has none of that name. One map lookup. */
    public CompiledPolicy get(String domain, String name) {
        return snapshot.policies().get(RoutingPolicy.key(domain, name));
    }

    public Snapshot snapshot() { return snapshot; }

    public List<Problem> problems() { return snapshot.problems(); }

    /** The doorbell: read everything again now. Never throws — a failed read keeps the snapshot. */
    public synchronized boolean reload() {
        try {
            load(false);
            return true;
        } catch (RuntimeException e) {
            log.warn("routing policies NOT reloaded — the last good set stays in service: {}", chain(e), e);
            return false;
        }
    }

    /** The poll: one cheap question to the store, a full read only when its answer changed. */
    public synchronized boolean reloadIfChanged() {
        try {
            String now = store.fingerprint();
            if (now.equals(snapshot.fingerprint())) return false;
            load(false);
            return true;
        } catch (RuntimeException e) {
            log.warn("routing policy poll failed — the last good set stays in service: {}", chain(e), e);
            return false;
        }
    }

    private synchronized void load(boolean first) {
        String fingerprint = store.fingerprint();
        List<RoutingPolicy> stored = store.list(null);
        Map<String, CompiledPolicy> before = snapshot.policies();
        Map<String, CompiledPolicy> next = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        for (RoutingPolicy p : stored) {
            CompiledPolicy old = before.get(p.key());
            if (old != null && old.source().equals(p)) {
                next.put(p.key(), old);                                  // the same row as before: keep the compiled object
                continue;
            }
            try {
                next.put(p.key(), types.compile(p));
                changes.add(new Change(p.key(), old == null ? "loaded" : "replaced", p.version(), p.type()));
            } catch (RuntimeException e) {
                problems.add(new Problem(p.key(), p.version(), e.getMessage(), Instant.now()));
                if (old != null) {
                    next.put(p.key(), old);
                    changes.add(new Change(p.key(), "kept-last-good", old.source().version(), "version " + p.version() + " does not compile: " + e.getMessage()));
                } else {
                    changes.add(new Change(p.key(), "not-loaded", p.version(), e.getMessage()));
                }
                log.warn("routing policy {} version {} does NOT compile{}: {}", p.key(), p.version(),
                    old == null ? " — it is not in service" : " — version " + old.source().version() + " stays in service", e.getMessage());
            }
        }
        for (String key : before.keySet()) {
            if (!next.containsKey(key)) changes.add(new Change(key, "removed", before.get(key).source().version(), ""));
        }
        snapshot = new Snapshot(Map.copyOf(next), List.copyOf(problems), fingerprint, Instant.now());
        if (first || !changes.isEmpty()) log.info("routing policies in service: {} ({} change(s), {} problem(s))", next.keySet(), changes.size(), problems.size());
        for (Change c : changes) for (Consumer<Change> l : listeners) {
            try {
                l.accept(c);
            } catch (RuntimeException e) {
                log.warn("a routing policy listener failed on {}: {}", c, chain(e), e);
            }
        }
    }

    @Override
    public void close() {
        if (poller != null) poller.shutdownNow();
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 600; c = c.getCause()) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
        }
        return sb.toString();
    }
}
