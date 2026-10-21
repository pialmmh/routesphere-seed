package com.telcobright.seed.sessionflow.api;

import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.internal.BudgetChild;
import com.telcobright.statewalk.channel.Channel;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.persistence.PersistenceMode;
import com.telcobright.statewalk.persistence.PersistenceProvider;
import com.telcobright.statewalk.registry.MachineSpec;
import com.telcobright.statewalk.registry.StatemachineRegistry;
import com.telcobright.statewalk.session.SessionSupervisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Runs one application's sessions on statewalk 4.0's registry (§B): the base supervisor
 * ({@code SessionSupervisor.spec(flow.name(), contexts, flow)} — the flow IS the handler), the application's signaling machine, the
 * budget child when the flow renews, the channel and its decoder (the kit: the first frame takes the supervisor and the signaling machine
 * from their pools), the lifetime cap, and — optionally — the store. Every machine is pooled at the settings' size; the registry resets
 * them all to IDLE at the session's end.
 *
 * <pre>
 *   RoutesphereEngine&lt;AdContext&gt; engine = RoutesphereEngine.of(adFlow, AdContext::new)
 *       .signaling(AdPlayback.spec(...))
 *       .channel(httpChannel, AdFrames::decode)
 *       .start();
 * </pre>
 *
 * @param <C> the application's context
 */
public final class RoutesphereEngine<C extends RoutesphereContext> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RoutesphereEngine.class);

    private final RoutesphereFlow<C> flow;
    private final StatemachineRegistry<C> registry;
    private final ScheduledExecutorService housekeeping;

    private RoutesphereEngine(Builder<C> b) {
        this.flow = b.flow;
        flow.kit().attachSessions(this::sessionOf);
        flow.publishWhatWasLeftInTheAir();                 // R1-6: before the first session, what a stopped process left in the air
        this.registry = buildRegistry(b);
        this.housekeeping = startSlotReconciler();
        RoutesphereSettings s = flow.kit().settings();
        log.info("[{}] routesphere flow up: a pool of {}, timers {}, lifetime cap {} s, signaling {}, budget child {}", flow.name(), s.pool(),
            s.timings(), s.globalTimeoutSec(), b.signaling.name(), flow.renewsBudget() ? "every " + s.reservePeriodSec() + " s" : "none");
    }

    public static <C extends RoutesphereContext> Builder<C> of(RoutesphereFlow<C> flow, Supplier<C> contexts) { return new Builder<>(flow, contexts); }

    public static final class Builder<C extends RoutesphereContext> {
        private final RoutesphereFlow<C> flow;
        private final Supplier<C> contexts;
        private MachineSpec<?> signaling;
        private final List<MachineSpec<?>> children = new ArrayList<>();
        private Channel<?, ?> channel;
        private BiFunction<String, ?, Delivery> decoder;
        private PersistenceMode persistenceMode = PersistenceMode.OFF;
        private Supplier<? extends PersistenceProvider> store;

        private Builder(RoutesphereFlow<C> flow, Supplier<C> contexts) {
            this.flow = Objects.requireNonNull(flow, "flow");
            this.contexts = Objects.requireNonNull(contexts, "contexts");
        }

        /** The application's ONE signaling machine (IDLE first, an {@code up} on every transition, Reject and Stop in every live state). */
        public Builder<C> signaling(MachineSpec<?> spec) { this.signaling = Objects.requireNonNull(spec, "signaling"); return this; }

        /** A helper child of the application's own (pooled like the rest). */
        public Builder<C> child(MachineSpec<?> spec) { children.add(Objects.requireNonNull(spec, "child")); return this; }

        /** The wire: the channel and its decoder (a frame → a {@code Delivery} to the signaling machine, or null to ignore it). */
        public <I> Builder<C> channel(Channel<?, I> channel, BiFunction<String, I, Delivery> decoder) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.decoder = Objects.requireNonNull(decoder, "decoder");
            return this;
        }

        /**
         * The store (statewalk's one row per session). §L.4: use a POOLED {@code DataSource}; the lane's table is the flow's name. §L.2: a
         * persisting flow that renews must renew at most once a minute — refused at {@link #start} otherwise.
         */
        public Builder<C> persistence(PersistenceMode mode, Supplier<? extends PersistenceProvider> provider) {
            this.persistenceMode = Objects.requireNonNull(mode, "mode");
            this.store = Objects.requireNonNull(provider, "provider");
            return this;
        }

        public RoutesphereEngine<C> start() {
            if (signaling == null) throw new IllegalStateException("[" + flow.name() + "] a routesphere flow needs its signaling machine (.signaling(spec))");
            if (channel == null) throw new IllegalStateException("[" + flow.name() + "] a routesphere flow needs its channel and decoder (.channel(channel, decoder))");
            refuseAFastPersistedCadence();
            return new RoutesphereEngine<>(this);
        }

        private void refuseAFastPersistedCadence() {
            RoutesphereSettings s = flow.kit().settings();
            boolean persists = persistenceMode != PersistenceMode.OFF;
            if (persists && flow.renewsBudget() && s.reservePeriodSec() < RoutesphereSettings.MIN_PERSISTED_RENEWAL_SEC) {
                throw new IllegalStateException("[" + flow.name() + "] the budget child renews every " + s.reservePeriodSec() + " s on a persisting registry:"
                    + " every renewal rewrites the session's row — at least " + RoutesphereSettings.MIN_PERSISTED_RENEWAL_SEC + " s (§L.2)");
            }
        }
    }

    // ── the door ────────────────────────────────────────────────────────────

    /** A frame for a session, targeted by the application's decoder — the registry's own door (the channel's inbound goes the same way). */
    public CompletableFuture<Void> deliver(String sessionId, Delivery delivery) { return registry.submitDelivery(sessionId, delivery); }

    /** Wait until the session has nothing more queued. */
    public boolean awaitSettled(String sessionId, long timeout, TimeUnit unit) throws InterruptedException {
        return registry.awaitSettled(sessionId, timeout, unit);
    }

    public boolean isLive(String sessionId) { return registry.hasAny(sessionId); }

    /** The supervisor's state of a live session, or null. */
    public String stateOf(String sessionId) { return registry.supervisorStateOf(sessionId); }

    /** A live session's context, or null. */
    public C sessionOf(String sessionId) { return registry == null ? null : registry.supervisorContextOf(sessionId); }

    public RoutesphereFlow<C> flow() { return flow; }

    /** statewalk's registry, for what this class does not wrap (pool statistics, child states, maintenance). */
    public StatemachineRegistry<C> registry() { return registry; }

    public PoolStats stats() {
        var c = flow.kit().counters();
        return new PoolStats(flow.kit().settings().pool(), registry.activeCellCount(), c.machinesBuilt.get(), c.launched.get(), c.busy.get(),
            c.ended.get(), c.cdrPublished.get(), c.cdrLost.get(), c.owed.get(), flow.kit().slots().held());
    }

    /** Stop: statewalk drives every live session to its end first (FAILED, the forced failover's reason), so each still settles and records. */
    @Override
    public void close() {
        if (housekeeping != null) housekeeping.shutdownNow();
        registry.shutdown();
        log.info("[{}] routesphere flow stopped: {}", flow.name(), stats());
    }

    // ── building ────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private StatemachineRegistry<C> buildRegistry(Builder<C> b) {
        RoutesphereSettings s = flow.kit().settings();
        StatemachineRegistry.Builder<C> rb = StatemachineRegistry.<C>builder(flow.name())
            .supervisor(SessionSupervisor.spec(flow.name(), b.contexts, flow), s.pool())
            .signaling(b.signaling, s.pool());
        if (flow.renewsBudget()) rb.child(BudgetChild.spec(flow), s.pool());
        for (MachineSpec<?> child : b.children) rb.child(child, s.pool());
        rb.channel((Channel) b.channel, (BiFunction) b.decoder);
        if (b.store != null) rb.persistence(b.persistenceMode, b.store);
        return rb.threads(s.threads())
            .maxConcurrent(s.pool())
            .globalTimeout(s.globalTimeoutSec(), TimeUnit.SECONDS, SessionSupervisor.FAILED)
            .debug(s.debug())
            .build();
    }

    /** A slot whose session is gone is given back: the counters heal themselves instead of blocking a partner. */
    private ScheduledExecutorService startSlotReconciler() {
        long period = flow.kit().settings().slotReconcileSec();
        if (period <= 0) return null;
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, flow.name() + "-slot-reconciler");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(this::reconcileSlots, period, period, TimeUnit.SECONDS);
        return timer;
    }

    private void reconcileSlots() {
        try {
            flow.kit().slots().reconcile(registry::hasAny);
        } catch (RuntimeException e) {
            log.warn("[{}] the slot reconciler's sweep failed: {}", flow.name(), e.toString());
        }
    }
}
