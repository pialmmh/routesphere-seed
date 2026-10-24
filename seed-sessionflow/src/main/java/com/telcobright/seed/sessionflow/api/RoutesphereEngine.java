package com.telcobright.seed.sessionflow.api;

import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.internal.BootstrapRoad;
import com.telcobright.seed.sessionflow.internal.BudgetChild;
import com.telcobright.seed.sessionflow.internal.BusyAtTheDoor;
import com.telcobright.seed.sessionflow.internal.DoorChannel;
import com.telcobright.statewalk.channel.Channel;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.persistence.PersistenceMode;
import com.telcobright.statewalk.persistence.PersistenceProvider;
import com.telcobright.statewalk.registry.MachineSpec;
import com.telcobright.statewalk.registry.StatemachineRegistry;
import com.telcobright.statewalk.session.SessionSupervisor;
import com.telcobright.statewalk.session.events.SessionStart;
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
 * Runs one application's sessions on statewalk 4.1's registry (§B): the lane's supervisor class ({@link RoutesphereSupervisor}: statewalk's
 * final {@code SessionSupervisor}, the flow IS the handler), the application's signaling machine, the
 * budget child when the flow renews, the channel and its decoder (the kit: the first frame takes the supervisor and the signaling machine
 * from their pools), the lifetime cap, and — optionally — the store. Every machine is pooled at the settings' size; the registry resets
 * them all to IDLE at the session's end.
 *
 * <pre>
 *   RoutesphereEngine&lt;AdContext&gt; engine = RoutesphereEngine.of(new AdSupervisor(adFlow))
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
        flow.kit().bootstrapRoad().ifPresent(road -> road.start(flow::topicsOf));    // R9: every served root's topics (a kit from the bootstrap)
        this.registry = buildRegistry(b);
        this.housekeeping = startSlotReconciler();
        RoutesphereSettings s = flow.kit().settings();
        log.info("[{}] routesphere flow up: a pool of {}, timers {}, lifetime cap {} s, signaling {}, budget child {}", flow.name(), s.pool(),
            s.timings(), s.globalTimeoutSec(), b.signaling.name(), flow.renewsBudget() ? "every " + s.reservePeriodSec() + " s" : "none");
    }

    /** The ONE door (R6): the lane's own supervisor class — no lane runs without it. */
    public static <C extends RoutesphereContext> Builder<C> of(RoutesphereSupervisor<C> supervisor) { return new Builder<>(supervisor); }

    public static final class Builder<C extends RoutesphereContext> {
        private final RoutesphereSupervisor<C> supervisor;
        private final RoutesphereFlow<C> flow;
        private MachineSpec<?> signaling;
        private final List<MachineSpec<?>> children = new ArrayList<>();
        private Channel<?, ?> channel;
        private BiFunction<String, ?, Delivery> decoder;
        private PersistenceMode persistenceMode = PersistenceMode.OFF;
        private Supplier<? extends PersistenceProvider> store;

        private Builder(RoutesphereSupervisor<C> supervisor) {
            this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
            this.flow = supervisor.flow();
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
         * persisting flow that renews must renew at most once a minute — refused at {@link #start} otherwise. P1-F3: RESUME and the kit's
         * journal of the sessions in the air are two recovery roads — refused together at {@link #start}.
         */
        public Builder<C> persistence(PersistenceMode mode, Supplier<? extends PersistenceProvider> provider) {
            this.persistenceMode = Objects.requireNonNull(mode, "mode");
            this.store = Objects.requireNonNull(provider, "provider");
            return this;
        }

        public RoutesphereEngine<C> start() {
            if (signaling == null) throw new IllegalStateException("[" + flow.name() + "] a routesphere flow needs its signaling machine (.signaling(spec))");
            if (channel == null) throw new IllegalStateException("[" + flow.name() + "] a routesphere flow needs its channel and decoder (.channel(channel, decoder))");
            refuseASignalingWithoutItsFactsSample();
            refuseAFastPersistedCadence();
            refuseTwoRecoveryRoads();
            return new RoutesphereEngine<>(this);
        }

        /**
         * R2 · The base finds a session's root by its facts' ingress, and statewalk's {@code SessionStart} carries any facts: the lane's
         * signaling machine SHOWS the facts it starts a session with — a {@code SessionStart} sample of {@code RoutesphereFacts}
         * ({@code MachineSpec.Builder.upSample}). Refused in words otherwise: every session would be refused {@code NO_TENANT}. (With a store,
         * statewalk 4.1 also writes the sample and reads it back at build: facts that cannot be kept in the row fail there.)
         */
        private void refuseASignalingWithoutItsFactsSample() {
            List<SessionStart> starts = signaling.upSamples().stream().filter(SessionStart.class::isInstance).map(SessionStart.class::cast).toList();
            if (starts.isEmpty()) {
                throw new IllegalStateException("[" + flow.name() + "] the signaling machine '" + signaling.name() + "' shows no SessionStart sample — add"
                    + " .upSample(new SessionStart(<the lane's RoutesphereFacts>)) to its spec: the base finds the session's root by its facts' ingress,"
                    + " and without it every session would be refused NO_TENANT");
            }
            for (SessionStart start : starts) {
                if (start.facts() instanceof RoutesphereFacts) continue;
                throw new IllegalStateException("[" + flow.name() + "] the signaling machine '" + signaling.name() + "': its SessionStart sample carries "
                    + start.facts().getClass().getName() + ", not RoutesphereFacts — they name no ingress: every session would be refused NO_TENANT");
            }
        }

        /**
         * P1-F3 · ONE recovery road per lane: the journal of the sessions in the air (the next start closes a dead process's sessions, charged
         * as reserved, LOST_AT_RESTART) OR statewalk's store in RESUME (the next node restores them and ends them itself). Both = a session in
         * both is closed by the journal and then restored and ended again. The journal beside a store that only SAVEs (or none) is allowed.
         */
        private void refuseTwoRecoveryRoads() {
            if (persistenceMode != PersistenceMode.RESUME || !flow.kit().journal().keeps()) return;
            throw new IllegalStateException("[" + flow.name() + "] one recovery road per lane: the kit keeps a journal of the sessions in the air ("
                + flow.kit().journal().where() + ") AND the store RESUMEs them — a session in both would be closed by the journal at the start"
                + " (charged as reserved, LOST_AT_RESTART) and then restored and ended again by statewalk. Keep the journal with the store at SAVE"
                + " or OFF, or RESUME with no journal (SessionJournals.none())");
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

    /**
     * A frame for a session, targeted by the application's decoder — the registry's own door (the channel's inbound goes the same way).
     * It counts nothing: a refused frame is the caller's to count (a lane that counts its own {@code busy} here keeps doing so); a NEW
     * session's first frame counted is {@link #launch}.
     */
    public CompletableFuture<Void> deliver(String sessionId, Delivery delivery) { return registry.submitDelivery(sessionId, delivery); }

    /**
     * P1-F · A NEW session's first frame through the registry's door, COUNTED: the kit (a supervisor and the signaling machine) leaves its
     * pools, or the registry refuses it — a refusal because every machine is live is counted {@code busy}, as at the lane's channel's
     * inbound. The stage is the registry's own: it completes and fails as {@link #deliver}'s does.
     */
    public CompletableFuture<Void> launch(String sessionId, Delivery firstFrame) {
        return BusyAtTheDoor.counted(registry.submitDelivery(sessionId, firstFrame), flow.kit().counters());
    }

    // ── the ingress's two lines (the owner's shape of 2026-10-24): identifyRootLevelTenant, then launchStateMachine ──

    /**
     * The ingress's FIRST line: the lane's rule names the ROOT operator that owns this door — no session, no machine yet. Null = no root:
     * the ingress refuses at the door (nothing is recorded). The root rides to the machine in {@link RoutesphereFacts#root()}.
     */
    public String identifyRootLevelTenant(RoutesphereFacts facts) { return flow.identifyRootLevelTenant(facts); }

    /** The ingress's SECOND line: a NEW session's first frame through the registry, counted — the same as {@link #launch}. */
    public CompletableFuture<Void> launchStateMachine(String sessionId, Delivery firstFrame) { return launch(sessionId, firstFrame); }

    /** The same, for a lane whose first frame is the supervisor's {@code SessionStart} of these facts. */
    public CompletableFuture<Void> launchStateMachine(RoutesphereFacts facts) {
        return launch(facts.sessionId(), Delivery.supervisor(new SessionStart(facts)));
    }

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

    /**
     * statewalk's registry, for what this class does not wrap (pool statistics, child states, maintenance). Its channel is the lane's behind
     * the door's counter ({@code DoorChannel}; {@code lane()} is the lane's own object).
     */
    public StatemachineRegistry<C> registry() { return registry; }

    public PoolStats stats() {
        var c = flow.kit().counters();
        return new PoolStats(flow.kit().settings().pool(), registry.activeCellCount(), supervisorsBuilt(), c.launched.get(), c.busy.get(),
            c.ended.get(), c.cdrPublished.get(), c.cdrLost.get(), c.owed.get(), flow.kit().slots().held(), registry.activeIdCount());
    }

    /** P1-F · The supervisors the registry's pool ever built — statewalk's own count (the base builds no machine; each type has its pool). */
    private long supervisorsBuilt() {
        var pool = registry.poolStatistics(flow.name());
        return pool == null ? 0 : pool.totalCreated();
    }

    /** Stop: statewalk drives every live session to its end first (FAILED, the forced failover's reason), so each still settles and records. */
    @Override
    public void close() {
        if (housekeeping != null) housekeeping.shutdownNow();
        registry.shutdown();
        flow.kit().bootstrapRoad().ifPresent(BootstrapRoad::close);
        log.info("[{}] routesphere flow stopped: {}", flow.name(), stats());
    }

    // ── building ────────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private StatemachineRegistry<C> buildRegistry(Builder<C> b) {
        RoutesphereSettings s = flow.kit().settings();
        StatemachineRegistry.Builder<C> rb = StatemachineRegistry.<C>builder(flow.name())
            .supervisor(b.supervisor.spec(), s.pool())
            .signaling(b.signaling, s.pool());
        if (flow.renewsBudget()) rb.child(BudgetChild.spec(flow), s.pool());
        for (MachineSpec<?> child : b.children) rb.child(child, s.pool());
        rb.channel(new DoorChannel<>((Channel) b.channel, flow.kit().counters()), (BiFunction) b.decoder);    // P1-F: busy counted at the door
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
