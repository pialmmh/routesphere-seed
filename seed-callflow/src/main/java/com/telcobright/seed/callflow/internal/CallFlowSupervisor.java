package com.telcobright.seed.callflow.internal;

import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.api.CallFlow;
import com.telcobright.seed.callflow.api.CallFlowContext;
import com.telcobright.seed.callflow.api.CallFlowTimings;
import com.telcobright.seed.callflow.api.CallMachine;
import com.telcobright.seed.callflow.publishes.Preprocessed;
import com.telcobright.seed.callflow.publishes.ReserveTick;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.registry.InternalEventResolver;
import com.telcobright.statewalk.registry.Supervisor;
import com.telcobright.statewalk.session.AdmissionVerdict;
import com.telcobright.statewalk.session.SdrRecord;
import com.telcobright.statewalk.session.events.AdmissionDecided;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SignalingDeferred;
import com.telcobright.statewalk.session.events.SignalingDone;
import com.telcobright.statewalk.session.events.SignalingFailed;
import com.telcobright.statewalk.session.events.SignalingProgress;
import com.telcobright.statewalk.state.StateMap;

import java.util.concurrent.TimeUnit;

import static com.telcobright.seed.callflow.api.CallState.ACTIVE;
import static com.telcobright.seed.callflow.api.CallState.ADMITTED;
import static com.telcobright.seed.callflow.api.CallState.ADMITTING;
import static com.telcobright.seed.callflow.api.CallState.DEFERRED;
import static com.telcobright.seed.callflow.api.CallState.FAILED;
import static com.telcobright.seed.callflow.api.CallState.PREPROCESSING;
import static com.telcobright.seed.callflow.api.CallState.RINGING;
import static com.telcobright.seed.callflow.api.CallState.SUCCEEDED;
import static com.telcobright.seed.callflow.api.CallState.TEARING_DOWN;

/**
 * The machine of ONE call — the same class for every application. It only maps states and events onto the steps of its
 * {@link CallFlow}; it decides nothing itself.
 *
 * <p><b>Pooling.</b> A machine is taken from the pool for one call and goes back when the call reaches a final state.
 * Its only field is the flow, final and shared: a machine carries NOTHING of a call, so there is nothing a reset could
 * forget. The registry refuses at start-up any pooled machine with a field that is not final. On return the framework
 * clears the context, the ids and the timers and sets the machine idle; the next call starts in PREPROCESSING with its
 * own new context.
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
        routes.selfHandle(ReserveTick.class);
        flow.defineRoutes(routes);
    }

    // ── the graph ───────────────────────────────────────────────────────────

    @Override
    protected StateMap defineStates() {
        CallFlowTimings t = flow.timings();
        long ringingSec = t.hasRingingPhase() ? t.ringingSec() : t.admittedSec();
        return StateMap.builder()
            .initialState(PREPROCESSING)

            .state(PREPROCESSING)
                .interim()
                .timeout(t.preprocessingSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> me(self).preprocess())
                .on(Preprocessed.class, ADMITTING, (self, e) -> ((Preprocessed) e).ok())
                .on(Preprocessed.class, FAILED, null, (self, e) -> me(self).endWith(((Preprocessed) e).cause()))

            .state(ADMITTING)
                .interim()
                .timeout(t.admittingSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> me(self).admit())
                .on(AdmissionDecided.class, ADMITTED, (self, e) -> ((AdmissionDecided) e).accepted())
                .on(AdmissionDecided.class, FAILED, null, (self, e) -> me(self).endWith(((AdmissionDecided) e).cause()))

            .state(ADMITTED)
                .interim()
                .timeout(t.admittedSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> me(self).signal())
                .on(SignalingDone.class, ACTIVE, null, (self, e) -> me(self).answered((SignalingDone) e))
                .on(SignalingProgress.class, RINGING, (self, e) -> me(self).hasRingingPhase(), (self, e) -> me(self).progress((SignalingProgress) e))
                .stay(SignalingProgress.class, (self, e) -> me(self).progress((SignalingProgress) e))
                .stay(SignalingFailed.class, (self, e) -> me(self).signalingFailed((SignalingFailed) e))
                .on(SignalingDeferred.class, DEFERRED, null, (self, e) -> me(self).endWith(((SignalingDeferred) e).cause()))
                .stay(ServiceEnd.class, (self, e) -> me(self).abortBeforeAnswer((ServiceEnd) e))

            .state(RINGING)
                .interim()
                .timeout(ringingSec, TimeUnit.SECONDS, FAILED)
                .on(SignalingDone.class, ACTIVE, null, (self, e) -> me(self).answered((SignalingDone) e))
                .stay(SignalingProgress.class, (self, e) -> me(self).progress((SignalingProgress) e))
                .stay(SignalingFailed.class, (self, e) -> me(self).signalingFailed((SignalingFailed) e))
                .on(SignalingDeferred.class, DEFERRED, null, (self, e) -> me(self).endWith(((SignalingDeferred) e).cause()))
                .stay(ServiceEnd.class, (self, e) -> me(self).abortBeforeAnswer((ServiceEnd) e))

            .state(ACTIVE)
                .interim()
                .timeout(t.activeMaxSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> me(self).activate())
                .stay(ReserveTick.class, (self, e) -> me(self).renewReserve())
                .on(ServiceEnd.class, TEARING_DOWN, null, (self, e) -> me(self).endWith(((ServiceEnd) e).cause()))

            .state(TEARING_DOWN)
                .interim()
                .timeout(t.tearingDownSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> me(self).teardown())

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

    private void signal() {
        try {
            flow.signal(getContext(), this);
        } catch (RuntimeException e) {
            failNow(CallCause.INTERNAL_ERROR, "the signaling could not start: " + e);
        }
    }

    private boolean hasRingingPhase() { return flow.timings().hasRingingPhase(); }

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

    private void activate() {
        flow.active(getContext(), this);
        if (flow.kit().settings().reservePeriodSec() > 0) resolver.spawnChild(ReserveClock.TYPE, new ReserveClock.Started(getContext().activatedAtMs));
    }

    private void renewReserve() {
        C ctx = getContext();
        String cause = flow.reserveNextWindow(ctx);
        if (cause == null) return;
        endWith(cause);
        transitionTo(TEARING_DOWN);
    }

    private void teardown() {
        C ctx = getContext();
        flow.teardown(ctx, this);
        if (ctx.endCause == null) ctx.endCause = CallCause.NORMAL_CLEARING;
        transitionTo(flow.succeededNow(ctx) ? SUCCEEDED : FAILED);
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
    public void spawnChild(String childType, Object childContext) { resolver.spawnChild(childType, childContext); }

    @Override
    public void retireChildren() { resolver.cleanupChildren(); }
}
