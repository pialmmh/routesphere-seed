package com.telcobright.seed.callflow.api;

import com.telcobright.seed.callflow.dependencies.CallFlowSettings;
import com.telcobright.seed.callflow.internal.CallFlowSupervisor;
import com.telcobright.seed.callflow.internal.FlowCounters;
import com.telcobright.seed.callflow.internal.ReserveClock;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.machine.Machine;
import com.telcobright.statewalk.registry.DispatchResult;
import com.telcobright.statewalk.registry.RejectCause;
import com.telcobright.statewalk.registry.StatemachineRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs one application's calls: it owns the pool of machines and launches a machine for every call.
 *
 * <p><b>The pool.</b> The number of machines is fixed by the settings ({@code pool}). It is also the most calls that can
 * be live at once: a call that finds every machine taken is refused at the door ({@link CallCause#BUSY}) — it does not
 * wait and no extra machine is built for it. A machine goes back to the pool when its call reaches a final state: the
 * framework clears its context, ids and timers, and the next call gets it idle. A call that no state deadline ended is
 * killed by the global timeout and still ends with its settlement and its CDR.
 *
 * <pre>
 *   CallFlowEngine&lt;AdContext&gt; engine = CallFlowEngine.of(adFlow)
 *       .child("AdView", () -> new AdView(...))
 *       .start();
 *   LaunchResult door = engine.launch(ctx);          // ctx.sessionKey is the call id
 *   engine.deliver(callId, new EvAdShown());         // the wire's events
 * </pre>
 *
 * @param <C> the application's context
 */
public final class CallFlowEngine<C extends CallFlowContext> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CallFlowEngine.class);

    private final CallFlow<C> flow;
    private final StatemachineRegistry<C> registry;
    private final ScheduledExecutorService housekeeping;

    private CallFlowEngine(Builder<C> builder) {
        this.flow = builder.flow;
        this.registry = buildRegistry(builder);
        counters().machinesBuilt.decrementAndGet();      // the registry built one sample to check the type; it never serves a call
        this.housekeeping = startSlotReconciler();
        CallFlowSettings s = flow.kit().settings();
        log.info("[{}] call flow up: a pool of {} machines, deadlines {}, hung-machine killer at {} s, reserve period {} s, children {}",
            flow.name(), s.pool(), s.timings(), builder.killerAfterSec, s.reservePeriodSec(), builder.children.keySet());
        sayAdmissionBudget(s);
    }

    /**
     * The admission's time, said once at start: how long the candidates that pay may take, and how many slow answers of
     * the ledger fit in it. A ledger whose one answer may take longer than the whole budget is worth a WARN: its own
     * timeout is never reached, the budget cuts every slow call first.
     */
    private void sayAdmissionBudget(CallFlowSettings s) {
        long budgetMs = s.admissionBudgetMs();
        long slowestMs = flow.kit().ledger().slowestAnswerMs();
        if (slowestMs <= 0) {
            log.info("[{}] admission: {} ms for the candidates that pay (the ADMITTING deadline {} s minus {} ms kept for a free candidate); the ledger answers in the process",
                flow.name(), budgetMs, s.timings().admittingSec(), s.admissionReserveMs());
            return;
        }
        log.info("[{}] admission: {} ms for the candidates that pay (the ADMITTING deadline {} s minus {} ms kept for a free candidate); one ledger answer may take {} ms, so {} slow answer(s) fit one admission",
            flow.name(), budgetMs, s.timings().admittingSec(), s.admissionReserveMs(), slowestMs, budgetMs / slowestMs);
        if (slowestMs > budgetMs) {
            log.warn("[{}] the ledger's own timeout ({} ms) is longer than the whole admission budget ({} ms): every slow ledger call is cut by the budget, not by the ledger's timeout — shorten the ledger's read timeout or lengthen ADMITTING",
                flow.name(), slowestMs, budgetMs);
        }
    }

    public static <C extends CallFlowContext> Builder<C> of(CallFlow<C> flow) { return new Builder<>(flow); }

    public static final class Builder<C extends CallFlowContext> {
        private final CallFlow<C> flow;
        private final Map<String, Supplier<? extends Machine<?>>> children = new LinkedHashMap<>();
        private long killerAfterSec;

        private Builder(CallFlow<C> flow) {
            this.flow = flow;
            this.killerAfterSec = flow.kit().settings().globalTimeoutSec();
        }

        /** A child machine type of the application (its signaling). It is pooled like the call's own machine. */
        public Builder<C> child(String type, Supplier<? extends Machine<?>> factory) {
            children.put(type, factory);
            return this;
        }

        /**
         * For the tests of the killer itself, which sit in this package: a killer shorter than the settings allow, so
         * that it fires on a call no state deadline has ended. A product cannot reach this; its killer is the settings'.
         */
        Builder<C> killerAfterSec(long seconds) {
            this.killerAfterSec = seconds;
            return this;
        }

        /** Build the pool and open the door. Fails here, at start-up, when a pooled machine could carry a call's state. */
        public CallFlowEngine<C> start() { return new CallFlowEngine<>(this); }
    }

    // ── the door ────────────────────────────────────────────────────────────

    /**
     * Launch one call: take a machine from the pool and start it in PREPROCESSING. The context must be new and carry
     * the call id in {@code sessionKey}. The call then runs on its own; the caller may {@link #awaitSettled} it.
     */
    public LaunchResult launch(C ctx) {
        requireCallId(ctx);
        DispatchResult door = registry.dispatch(ctx.sessionKey, ctx);
        return door.accepted() ? launched() : refused(ctx, door.rejectCause());
    }

    private LaunchResult launched() {
        counters().launched.incrementAndGet();
        return LaunchResult.ok();
    }

    private LaunchResult refused(C ctx, RejectCause reason) {
        if (reason == RejectCause.CAPACITY_EXCEEDED) noteBusy();
        else log.warn("[{}] call {} was refused at the door: {}", flow.name(), ctx.sessionKey, reason);
        return LaunchResult.refused(reason);
    }

    /** A full pool refuses many calls in a row: it is counted for each, and said in the log for the first and every thousandth. */
    private void noteBusy() {
        long refusedSoFar = counters().busy.incrementAndGet();
        if (refusedSoFar == 1 || refusedSoFar % 1000 == 0) {
            log.warn("[{}] the pool of {} machines is full: {} call(s) refused at the door so far", flow.name(),
                flow.kit().settings().pool(), refusedSoFar);
        }
    }

    private static void requireCallId(CallFlowContext ctx) {
        if (ctx == null || ctx.sessionKey == null || ctx.sessionKey.isBlank()) {
            throw new IllegalArgumentException("a call needs its id in the context's sessionKey before it is launched");
        }
    }

    /**
     * Hand an event of the wire to a live call. The future completes when the call has processed it. A call that has
     * ended takes no event: the event is dropped, or the future fails when the id was never known.
     */
    public CompletableFuture<Void> deliver(String callId, StatemachineEvent event) {
        return registry.submitInbound(callId, event);
    }

    /** Wait until the call has nothing more queued: after a launch, it then waits for the wire or has ended. */
    public boolean awaitSettled(String callId, long timeout, TimeUnit unit) throws InterruptedException {
        return registry.awaitSettled(callId, timeout, unit);
    }

    /** What this call would come to, with no side effect and no machine. */
    public DryRun simulate(C ctx) { return flow.simulate(ctx); }

    // ── looking at the calls and the pool ───────────────────────────────────

    public boolean isLive(String callId) { return registry.hasAny(callId); }

    /** The state of a live call, or null when it is not live. */
    public String stateOf(String callId) { return registry.supervisorStateOf(callId); }

    public Optional<C> contextOf(String callId) { return Optional.ofNullable(registry.supervisorContextOf(callId)); }

    public PoolStats stats() {
        FlowCounters c = counters();
        return new PoolStats(flow.kit().settings().pool(), registry.activeIdCount(), c.machinesBuilt.get(), c.launched.get(), c.busy.get(),
            c.ended.get(), c.cdrPublished.get(), c.cdrLost.get(), c.owed.get(), flow.slots().held());
    }

    public CallFlow<C> flow() { return flow; }

    /** The framework's own registry, for what this class does not wrap. */
    public StatemachineRegistry<C> registry() { return registry; }

    /** Stop: every live call is driven to its end first, so each still settles and writes its CDR. */
    @Override
    public void close() {
        if (housekeeping != null) housekeeping.shutdownNow();
        registry.shutdown();
        log.info("[{}] call flow stopped: {}", flow.name(), stats());
    }

    // ── building ────────────────────────────────────────────────────────────

    private StatemachineRegistry<C> buildRegistry(Builder<C> builder) {
        CallFlowSettings s = flow.kit().settings();
        StatemachineRegistry.Builder<C> registryBuilder = StatemachineRegistry.<C>builder(flow.name())
            .supervisor(flow.name(), this::newMachine, s.pool());
        builder.children.forEach((type, factory) -> registryBuilder.child(type, factory, s.pool()));
        if (s.reservePeriodSec() > 0) registryBuilder.child(ReserveClock.TYPE, () -> new ReserveClock(s.reservePeriodSec()), s.pool());
        return registryBuilder
            .threads(s.threads())
            .maxConcurrent(s.pool())
            .globalTimeout(builder.killerAfterSec, TimeUnit.SECONDS, CallState.FAILED)
            .debug(s.debug())
            .build();
    }

    private CallFlowSupervisor<C> newMachine() {
        counters().machinesBuilt.incrementAndGet();
        return new CallFlowSupervisor<>(flow);
    }

    /** A slot whose call is gone is given back: the counters heal themselves instead of blocking a partner. */
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
            flow.slots().reconcile(registry::hasAny);
        } catch (RuntimeException e) {
            log.warn("[{}] the slot reconciler's sweep failed: {}", flow.name(), e.toString());
        }
    }

    private FlowCounters counters() { return flow.counters(); }
}
