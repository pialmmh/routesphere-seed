package com.telcobright.seed.callflow.internal;

import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.api.CallFlow;
import com.telcobright.seed.callflow.api.CallFlowContext;
import com.telcobright.seed.callflow.api.CallFlowTimings;
import com.telcobright.seed.callflow.api.CallMachine;
import com.telcobright.seed.callflow.api.TierSettlement;
import com.telcobright.seed.callflow.publishes.BudgetStart;
import com.telcobright.seed.callflow.publishes.Preprocessed;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.registry.InternalEventResolver;
import com.telcobright.statewalk.registry.Supervisor;
import com.telcobright.statewalk.session.AdmissionVerdict;
import com.telcobright.statewalk.session.SdrRecord;
import com.telcobright.statewalk.session.events.AdmissionDecided;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SettleRequest;
import com.telcobright.statewalk.session.events.Settled;
import com.telcobright.statewalk.session.events.SignalingDeferred;
import com.telcobright.statewalk.session.events.SignalingDone;
import com.telcobright.statewalk.session.events.SignalingFailed;
import com.telcobright.statewalk.session.events.SignalingProgress;
import com.telcobright.statewalk.state.StateMap;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.telcobright.seed.callflow.api.CallState.ACTIVE;
import static com.telcobright.seed.callflow.api.CallState.ADMITTED;
import static com.telcobright.seed.callflow.api.CallState.ADMITTING;
import static com.telcobright.seed.callflow.api.CallState.DEFERRED;
import static com.telcobright.seed.callflow.api.CallState.FAILED;
import static com.telcobright.seed.callflow.api.CallState.PREPROCESSING;
import static com.telcobright.seed.callflow.api.CallState.SUCCEEDED;
import static com.telcobright.seed.callflow.api.CallState.TEARING_DOWN;

/**
 * The machine of ONE call — the same class for every application. It only maps states and events onto the steps of its
 * {@link CallFlow}; it decides nothing itself.
 *
 * <p><b>The graph is generic.</b> {@code PREPROCESSING → ADMITTING → ADMITTED → ACTIVE → TEARING_DOWN → SUCCEEDED | FAILED}
 * (and {@code DEFERRED}, an end by design before any service) — the library's session graph with the base's preprocessing in
 * front. No protocol word is a state of it: ringing, early media, playing, submitting live in the application's own signaling
 * child, which reports them as {@code SignalingProgress} — a stay in ADMITTED, the first one stamped for the PDD. ADMITTED's one
 * deadline bounds the whole pre-answer phase; a protocol's own windows (the carrier's silence, the far end's ringing) are the
 * child's deadlines.
 *
 * <p><b>The balance child.</b> A call that settles asynchronously, or renews its reserve every period, gets a
 * {@link LevelBalanceTracker} at ADMITTED.entry, beside its signaling (C9). It is the base's own: a signaling retry never retires
 * it. ACTIVE.entry tells it to start its cadence ({@link BudgetStart}); TEARING_DOWN asks it to settle ({@code SettleRequest}) and
 * takes its {@code Settled} — the per-tier results onto the call (C18), then the success rule. A call with neither has no child
 * and settles inline in TEARING_DOWN, as the ad does.
 *
 * <p><b>Pooling.</b> A machine is taken from the pool for one call and goes back when the call reaches a final state.
 * Its only field is the flow, final and shared: a machine carries NOTHING of a call, so there is nothing a reset could
 * forget. The registry refuses at start-up any pooled machine with a field that is not final. On return the framework
 * clears the context, the ids and the timers and sets the machine idle; the next call starts in PREPROCESSING with its
 * own new context.
 *
 * <p><b>The caller may leave at any time.</b> A {@code ServiceEnd} that arrives before the answer — while the call is
 * still being preprocessed or admitted, too — ends the call with that cause. A step runs to its end first (an event
 * never interrupts a step), so an admission that had just reserved is settled like any other end: nothing is left held.
 *
 * <p><b>Every end runs the same end.</b> A normal end, a refusal, a state's deadline, the registry's global timeout
 * (a hung machine) and a shutdown all arrive in a final state, and every final state runs {@link #close}: the flow's
 * end (settle, slot, CDR) and one session record. {@link #close} runs once per call.
 */
public final class CallFlowSupervisor<C extends CallFlowContext> extends Supervisor<C> implements CallMachine {

    /** The cause hint the framework passes for a state's own deadline. */
    private static final String TIMEOUT_HINT = "TimeoutEvent";

    private final CallFlow<C> flow;

    public CallFlowSupervisor(CallFlow<C> flow) {
        super(routes -> declareRoutes(routes, flow));
        this.flow = flow;
    }

    private static void declareRoutes(InternalEventResolver routes, CallFlow<?> flow) {
        routes.selfHandle(Preprocessed.class);
        routes.selfHandle(AdmissionDecided.class);
        routes.selfHandle(SignalingProgress.class);
        routes.selfHandle(SignalingDone.class);
        routes.selfHandle(SignalingFailed.class);
        routes.selfHandle(SignalingDeferred.class);
        routes.selfHandle(ServiceEnd.class);
        routes.selfHandle(Settled.class);
        if (flow.usesBalanceChild()) {
            routes.forwardTo(LevelBalanceTracker.TYPE, BudgetStart.class);
            routes.forwardTo(LevelBalanceTracker.TYPE, SettleRequest.class);
        }
        flow.defineRoutes(routes);
    }

    // ── the graph ───────────────────────────────────────────────────────────

    @Override
    protected StateMap defineStates() {
        CallFlowTimings t = flow.timings();
        return StateMap.builder()
            .initialState(PREPROCESSING)

            .state(PREPROCESSING)
                .interim()
                .timeout(t.preprocessingSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> me(self).preprocess())
                .on(Preprocessed.class, ADMITTING, (self, e) -> ((Preprocessed) e).ok())
                .on(Preprocessed.class, FAILED, null, (self, e) -> me(self).endWith(((Preprocessed) e).cause()))
                .stay(ServiceEnd.class, (self, e) -> me(self).abortBeforeAnswer((ServiceEnd) e))

            .state(ADMITTING)
                .interim()
                .timeout(t.admittingSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> me(self).admit())
                .on(AdmissionDecided.class, ADMITTED, (self, e) -> ((AdmissionDecided) e).accepted())
                .on(AdmissionDecided.class, FAILED, null, (self, e) -> me(self).endWith(((AdmissionDecided) e).cause()))
                .stay(ServiceEnd.class, (self, e) -> me(self).abortBeforeAnswer((ServiceEnd) e))

            .state(ADMITTED)
                .interim()
                .timeout(t.admittedSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> me(self).admitted())
                .on(SignalingDone.class, ACTIVE, null, (self, e) -> me(self).answered((SignalingDone) e))
                .stay(SignalingProgress.class, (self, e) -> me(self).progress((SignalingProgress) e))
                .stay(SignalingFailed.class, (self, e) -> me(self).signalingFailed((SignalingFailed) e))
                .on(SignalingDeferred.class, DEFERRED, null, (self, e) -> me(self).endWith(((SignalingDeferred) e).cause()))
                .stay(ServiceEnd.class, (self, e) -> me(self).abortBeforeAnswer((ServiceEnd) e))

            .state(ACTIVE)
                .interim()
                .timeout(t.activeMaxSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> me(self).activate())
                .on(ServiceEnd.class, TEARING_DOWN, null, (self, e) -> me(self).endWith(((ServiceEnd) e).cause()))

            .state(TEARING_DOWN)
                .interim()
                .timeout(t.tearingDownSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> me(self).teardown())
                .on(Settled.class, SUCCEEDED, (self, e) -> me(self).settled((Settled) e))
                .on(Settled.class, FAILED)

            .state(SUCCEEDED)
                .finalState()
                .timeout(1, TimeUnit.SECONDS, SUCCEEDED)
                .onEntry(self -> me(self).close(SUCCEEDED))

            .state(FAILED)
                .finalState()
                .timeout(1, TimeUnit.SECONDS, FAILED)
                .onEntry(self -> me(self).close(FAILED))

            .state(DEFERRED)
                .finalState()
                .timeout(1, TimeUnit.SECONDS, DEFERRED)
                .onEntry(self -> me(self).close(DEFERRED))

            .build();
    }

    @SuppressWarnings("unchecked")
    private static <C extends CallFlowContext> CallFlowSupervisor<C> me(Object self) { return (CallFlowSupervisor<C>) self; }

    // ── the states' work: each one line of the flow ─────────────────────────

    private void preprocess() {
        String refusal = flow.preprocess(getContext());
        publishEvent(new Preprocessed(refusal == null, refusal));
    }

    private void admit() {
        AdmissionVerdict verdict = flow.admit(getContext(), StepMode.LIVE);
        publishEvent(new AdmissionDecided(verdict.accepted(), verdict.rejectCause()));
    }

    /** C9: the balance child first — it holds the tiers for the whole call — then the signaling. */
    private void admitted() {
        if (flow.usesBalanceChild()) resolver.spawnChild(LevelBalanceTracker.TYPE, getContext());
        signal();
    }

    private void signal() {
        try {
            flow.signal(getContext(), this);
        } catch (RuntimeException e) {
            failNow(CallCause.INTERNAL_ERROR, "the signaling could not start: " + e);
        }
    }

    /** Ringing, early media, a beacon: a stay in ADMITTED. The first one is the PDD; the deadline of ADMITTED is not re-armed. */
    private void progress(SignalingProgress report) { flow.progress(getContext(), report.phase()); }

    private void signalingFailed(SignalingFailed failure) {
        boolean retrying;
        try {
            retrying = flow.retry(getContext(), failure.cause(), this);
        } catch (RuntimeException e) {
            failNow(CallCause.INTERNAL_ERROR, "the retry could not start: " + e);
            return;
        }
        if (!retrying) failNow(failure.cause(), null);
    }

    private void abortBeforeAnswer(ServiceEnd end) { failNow(end.cause(), null); }

    private void answered(SignalingDone done) { flow.answered(getContext(), done.grant()); }

    /** C13: the service runs; the balance child may start its cadence (C14). */
    private void activate() {
        flow.active(getContext(), this);
        if (flow.usesBalanceChild()) publishEvent(new BudgetStart());
    }

    /** C15/C16: stop the service, then settle — inline, or by asking the balance child and waiting for its answer. */
    private void teardown() {
        C ctx = getContext();
        flow.teardown(ctx, this);
        if (ctx.endCause == null) ctx.endCause = CallCause.NORMAL_CLEARING;
        if (flow.balanceChildSettles()) {
            publishEvent(new SettleRequest());
            return;
        }
        transitionTo(flow.succeededNow(ctx) ? SUCCEEDED : FAILED);
    }

    /** C18: the balance child settled — the per-tier results are the call's; then the success rule decides the end. */
    @SuppressWarnings("unchecked")
    private boolean settled(Settled done) {
        C ctx = getContext();
        if (done.totals() instanceof List<?> tiers) ctx.settlements = (List<TierSettlement>) tiers;
        return flow.succeededNow(ctx);
    }

    /** The first cause a call is given is its cause: a later one never replaces it. */
    private void endWith(String cause) {
        C ctx = getContext();
        if (ctx.endCause == null) ctx.endCause = cause;
    }

    private void failNow(String cause, String note) {
        C ctx = getContext();
        if (note != null) ctx.history.note(ctx.historyName(), note);
        if (ctx.endCause == null) ctx.endCause = cause == null ? CallCause.INTERNAL_ERROR : cause;
        transitionTo(FAILED);
    }

    // ── the history, and the cause of a deadline or a kill ──────────────────

    /**
     * Every transition goes into the call's history. A call that fails with no cause yet gets one here: a state's own
     * deadline by the state it left; a transition with no event behind it is the registry's global timeout — a hung machine.
     */
    @Override
    protected void onTransitioned(String fromState, String toState, String causeHint) {
        C ctx = getContext();
        if (ctx == null) return;
        if (FAILED.equals(toState) && ctx.endCause == null && fromState != null) {
            ctx.endCause = TIMEOUT_HINT.equals(causeHint) ? flow.causeOfTimeout(fromState) : CallCause.HUNG_MACHINE;
        }
        ctx.history.transition(ctx.historyName(), fromState, toState, causeHint);
    }

    /** The registry forced the call to its end: a shutdown, or the registry's own failure. */
    @Override
    protected void onForcedFailover(String reason) {
        C ctx = getContext();
        if (ctx == null) return;
        boolean shutdown = reason != null && reason.toLowerCase().contains("shutdown");
        if (ctx.endCause == null) ctx.endCause = shutdown ? CallCause.SYSTEM_SHUTDOWN : CallCause.HUNG_MACHINE;
        ctx.history.note(ctx.historyName(), "forced to end: " + reason);
    }

    // ── the end of every call ───────────────────────────────────────────────

    private void close(String outcome) {
        C ctx = getContext();
        if (ctx == null || ctx.outcome != null) return;        // already closed, or a forced end raced the reset
        stampEnd(ctx, outcome, flow.kit().clock().millis());
        flow.end(ctx, outcome, this);
        writeSessionRecord(ctx, outcome);
    }

    private static void stampEnd(CallFlowContext ctx, String outcome, long nowMs) {
        ctx.outcome = outcome;
        ctx.endedAtMs = nowMs;
        if (ctx.endCause == null) ctx.endCause = SUCCEEDED.equals(outcome) ? CallCause.NORMAL_CLEARING : CallCause.HUNG_MACHINE;
    }

    private void writeSessionRecord(C ctx, String outcome) {
        SdrRecord record = new SdrRecord(ctx.sessionKey, outcome, ctx.endCause, ctx.createdAtMs, ctx.activatedAtMs, ctx.endedAtMs,
            ctx.attempts, flow.sdrOf(ctx, outcome), ctx.history.snapshot(), ctx.history.droppedCount());
        try {
            flow.kit().sdrSink().write(record);
        } catch (RuntimeException e) {
            LOG.error("[{}] the session record of call {} ({} {}) was NOT written: {}", flow.name(), ctx.sessionKey, outcome, ctx.endCause, e.toString());
        }
    }

    // ── what a step may do with this machine ────────────────────────────────

    @Override
    public String callId() { return getMachineId(); }

    @Override
    public void publish(StatemachineEvent event) { publishEvent(event); }

    @Override
    public void spawnChild(String childType, Object childContext) {
        getContext().spawnedChildren.add(childType);
        resolver.spawnChild(childType, childContext);
    }

    /** Only the children the application spawned for the attempt: the base's own balance child outlives every attempt. */
    @Override
    public void retireChildren() {
        C ctx = getContext();
        for (String type : ctx.spawnedChildren) resolver.cleanupChild(type);
        ctx.spawnedChildren.clear();
    }
}
