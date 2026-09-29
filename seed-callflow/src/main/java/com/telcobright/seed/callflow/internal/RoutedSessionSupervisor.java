package com.telcobright.seed.callflow.internal;

import com.telcobright.seed.callflow.api.AdCause;
import com.telcobright.seed.callflow.api.PreprocessVerdict;
import com.telcobright.seed.callflow.api.RoutedSessionTimings;
import com.telcobright.seed.callflow.publishes.Preprocessed;
import com.telcobright.statewalk.registry.InternalEventResolver;
import com.telcobright.statewalk.registry.Supervisor;
import com.telcobright.statewalk.session.AdmissionVerdict;
import com.telcobright.statewalk.session.SdrRecord;
import com.telcobright.statewalk.session.SdrSink;
import com.telcobright.statewalk.session.SessionContext;
import com.telcobright.statewalk.session.events.AdmissionDecided;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SettleRequest;
import com.telcobright.statewalk.session.events.Settled;
import com.telcobright.statewalk.session.events.SignalingDeferred;
import com.telcobright.statewalk.session.events.SignalingDone;
import com.telcobright.statewalk.session.events.SignalingFailed;
import com.telcobright.statewalk.session.events.SignalingProgress;
import com.telcobright.statewalk.state.StateMap;

import java.util.concurrent.TimeUnit;

/**
 * statewalk 3.2.0's {@code SessionSupervisor} graph with ONE state in front (design §2.1, decision D9: built here on
 * {@link Supervisor}, promoted into statewalk 3.3.0 by the local side later — {@code SessionSupervisor.defineStates()} is
 * final, so a subclass cannot add the state):
 *
 * <pre>
 *   PREPROCESSING ──ok──► ADMITTING ──accept──► ADMITTED ──SignalingDone──► ACTIVE ──ServiceEnd──► TEARING_DOWN ──Settled──► SUCCEEDED
 *        │                    │                     │  ▲__retry(nextAttempt)     │                       │
 *        └── refused ─────────┴── reject ───────────┴── SignalingFailed / abort ─┴───(dead-man)──────────┴──────────────────► FAILED
 *                                                   │
 *                                                   └── SignalingDeferred ──► DEFERRED
 * </pre>
 *
 * PREPROCESSING does the switch's routing work BEFORE admission (the rule match, the dialplan walk, the candidates, the
 * inversion into a call payload) and publishes {@link Preprocessed}. Every state is timed; every timeout ends in FAILED with
 * a named cause: {@code PREPROCESS_TIMEOUT}, {@code ADMISSION_TIMEOUT}, {@code NOT_SHOWN}, {@code MAX_DURATION_REACHED},
 * {@code SETTLE_TIMEOUT}; the registry's global timeout (an imperative transition to FAILED with no event behind it) is
 * {@code HUNG_MACHINE}. Both terminals run {@link #onEnded} (the CDR) and write exactly one {@link SdrRecord}.
 *
 * <p><b>Back to PREPROCESSING on return to the pool is the framework's contract, not code here</b>: {@code Machine.resetForReuse()}
 * nulls the context and every id / timer and sets IDLE; the next {@code start()} enters {@code initialState} = PREPROCESSING with
 * the fresh context of the next borrow. The registry's pool validator refuses any mutable instance field on a subclass.
 *
 * <p>Subclass rules (enforced by the framework): only {@code final} fields; {@link #defineDomainRoutes} runs from the
 * constructor — reference event classes and child names only, never instance fields.
 */
public abstract class RoutedSessionSupervisor<C extends SessionContext> extends Supervisor<C> {

    public static final String PREPROCESSING = "PREPROCESSING";
    public static final String ADMITTING = "ADMITTING";
    public static final String ADMITTED = "ADMITTED";
    public static final String ACTIVE = "ACTIVE";
    public static final String TEARING_DOWN = "TEARING_DOWN";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    /** Ended before any service by design, not a failure: only from ADMITTED, via {@link SignalingDeferred}. */
    public static final String DEFERRED = "DEFERRED";

    /** The cause hint statewalk passes for a state timeout ({@code TimeoutEvent}'s simple name). */
    static final String TIMEOUT_HINT = "TimeoutEvent";

    // ─────────────────────────────────────────────────────────────────
    // The override surface
    // ─────────────────────────────────────────────────────────────────

    /** Per-state timeouts, from the domain's policy. Called lazily at start (fields are set). */
    protected abstract RoutedSessionTimings timings();

    /**
     * PREPROCESSING: the routing work before admission — resolve the tenant's context, bind the call source, match the
     * rule, walk the dialplan, find the candidates, invert into the payload. Runs synchronously in PREPROCESSING.entry on
     * the cell's chain; copy what it found into the context. Must not throw — a throw is a refusal with
     * {@code INTERNAL_ERROR} (the original preserved in the history). {@link PreprocessVerdict#pending()} = the answer arrives
     * later as a {@link Preprocessed} event through the registry.
     */
    protected abstract PreprocessVerdict preprocess(C ctx);

    /**
     * Admission: authorize, rate and DEBIT every tier. Runs synchronously in ADMITTING.entry on the cell's chain; copy
     * accepted data into the context here. Must not throw — a throw is a reject with the exception's toString as cause.
     */
    protected abstract AdmissionVerdict runAdmission(C ctx);

    /** Spawn the signaling child (and aux children) via {@code r.spawnChild(name, childCtx)}; child contexts share {@code ctx.history}. */
    protected abstract void spawnChildren(InternalEventResolver r, C ctx);

    /** Deliver the service. ACTIVE.entry. */
    protected abstract void onActive(C ctx);

    /** Stop the service. TEARING_DOWN.entry, and the FAILED backstop. */
    protected abstract void onTeardown(C ctx);

    /** The domain SDR payload; embedded in the generic {@link SdrRecord}. */
    protected abstract Object buildSdr(C ctx, String outcome);

    /** Where SDRs go. Return a final-field sink. */
    protected abstract SdrSink sdrSink();

    /** Child-forward routes ONLY (the base already self-handles its own vocabulary). Constructor-time: no field access. */
    protected abstract void defineDomainRoutes(InternalEventResolver r);

    /** Copy the grant payload of {@link SignalingDone} into the context. Runs as a transition action. */
    protected void onSignalingDone(C ctx, Object grant) { }

    /** Copy the totals payload of {@link Settled} into the context. */
    protected void onSettled(C ctx, Object totals) { }

    /** Informative signaling phase reports while ADMITTED. */
    protected void onSignalingProgress(C ctx, String phase) { }

    /** Retry decision after a signaling failure. Default: no retry. */
    protected boolean nextAttempt(C ctx, String failureCause) { return false; }

    /** Clean up the failed attempt's children before a retry respawn. Default retires every live child. */
    protected void cleanupBeforeRetry(InternalEventResolver r, C ctx) { r.cleanupChildren(); }

    /** Terminal-side domain work BEFORE the SDR is written (the ad: the CDR rows in one transaction). Never throws upward. */
    protected void onEnded(C ctx, String outcome) { }

    /** true = a budget child answers {@link SettleRequest} with {@link Settled}; false = teardown settles inline. */
    protected boolean settlesAsync() { return true; }

    /** Outcome rule: default = the session delivered service at some point. */
    protected boolean sessionSucceeded(C ctx) { return ctx.activatedAtMs > 0; }

    /**
     * The cause a state's TIMEOUT gives the session (design §2.1). A domain may rename one (the ad's ADMITTED window is
     * {@code NOT_SHOWN}); the defaults are the design's.
     */
    protected String timeoutCause(String timedOutState) {
        return switch (timedOutState) {
            case PREPROCESSING -> AdCause.PREPROCESS_TIMEOUT.name();
            case ADMITTING -> AdCause.ADMISSION_TIMEOUT.name();
            case ADMITTED -> AdCause.NOT_SHOWN.name();
            case ACTIVE -> AdCause.MAX_DURATION_REACHED.name();
            case TEARING_DOWN -> AdCause.SETTLE_TIMEOUT.name();
            default -> AdCause.INTERNAL_ERROR.name();
        };
    }

    // ─────────────────────────────────────────────────────────────────
    // Routing — base vocabulary self-handled; domain adds forwards
    // ─────────────────────────────────────────────────────────────────

    @Override
    protected final void defineRoutes(InternalEventResolver r) {
        r.selfHandle(Preprocessed.class);
        r.selfHandle(AdmissionDecided.class);
        r.selfHandle(SignalingProgress.class);
        r.selfHandle(SignalingDone.class);
        r.selfHandle(SignalingFailed.class);
        r.selfHandle(SignalingDeferred.class);
        r.selfHandle(ServiceEnd.class);
        r.selfHandle(Settled.class);
        defineDomainRoutes(r);
    }

    // ─────────────────────────────────────────────────────────────────
    // The graph
    // ─────────────────────────────────────────────────────────────────

    @Override
    protected final StateMap defineStates() {
        RoutedSessionTimings t = timings();
        return StateMap.builder()
            .initialState(PREPROCESSING)

            .state(PREPROCESSING)
                .interim()
                .timeout(t.preprocessingSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> ((RoutedSessionSupervisor<?>) self).preprocessNow())
                .on(Preprocessed.class, ADMITTING, (self, e) -> ((Preprocessed) e).ok())
                .on(Preprocessed.class, FAILED, null, (self, e) -> ((RoutedSessionSupervisor<?>) self).recordRefusal((Preprocessed) e))

            .state(ADMITTING)
                .interim()
                .timeout(t.admittingSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> ((RoutedSessionSupervisor<?>) self).admit())
                .on(AdmissionDecided.class, ADMITTED, (self, e) -> ((AdmissionDecided) e).accepted())
                .on(AdmissionDecided.class, FAILED)

            .state(ADMITTED)
                .interim()
                .timeout(t.admittedSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> ((RoutedSessionSupervisor<?>) self).firstSpawn())
                .on(SignalingDone.class, ACTIVE, null, (self, e) -> ((RoutedSessionSupervisor<?>) self).grantArrived((SignalingDone) e))
                .stay(SignalingProgress.class, (self, e) -> ((RoutedSessionSupervisor<?>) self).progress((SignalingProgress) e))
                .stay(SignalingFailed.class, (self, e) -> ((RoutedSessionSupervisor<?>) self).signalingFailed((SignalingFailed) e))
                .on(SignalingDeferred.class, DEFERRED, null, (self, e) -> ((RoutedSessionSupervisor<?>) self).recordDeferral((SignalingDeferred) e))
                .stay(ServiceEnd.class, (self, e) -> ((RoutedSessionSupervisor<?>) self).abortPreActive((ServiceEnd) e))

            .state(ACTIVE)
                .interim()
                .timeout(t.activeMaxSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> ((RoutedSessionSupervisor<?>) self).activate())
                .on(ServiceEnd.class, TEARING_DOWN, null, (self, e) -> ((RoutedSessionSupervisor<?>) self).recordEnd((ServiceEnd) e))

            .state(TEARING_DOWN)
                .interim()
                .timeout(t.tearingDownSec(), TimeUnit.SECONDS, FAILED)
                .onEntry(self -> ((RoutedSessionSupervisor<?>) self).teardown())
                .on(Settled.class, SUCCEEDED, (self, e) -> ((RoutedSessionSupervisor<?>) self).settleDecision((Settled) e))
                .on(Settled.class, FAILED)

            .state(SUCCEEDED)
                .finalState()
                .timeout(1, TimeUnit.SECONDS, SUCCEEDED)
                .onEntry(self -> ((RoutedSessionSupervisor<?>) self).close(SUCCEEDED))

            .state(FAILED)
                .finalState()
                .timeout(1, TimeUnit.SECONDS, FAILED)
                .onEntry(self -> ((RoutedSessionSupervisor<?>) self).close(FAILED))

            .state(DEFERRED)
                .finalState()
                .timeout(1, TimeUnit.SECONDS, DEFERRED)
                .onEntry(self -> ((RoutedSessionSupervisor<?>) self).close(DEFERRED))

            .build();
    }

    // ─────────────────────────────────────────────────────────────────
    // History tap + the cause of a timeout / a hung machine
    // ─────────────────────────────────────────────────────────────────

    /**
     * Every transition of the supervisor goes into the cell's history. A transition INTO FAILED with no cause yet names
     * the reason: a state timeout by the state it left ({@link #timeoutCause}); an imperative transition with no event
     * behind it (the registry's global timeout) is {@code HUNG_MACHINE}.
     */
    @Override
    protected final void onTransitioned(String fromState, String toState, String causeHint) {
        C ctx = getContext();
        if (ctx == null) return;
        if (FAILED.equals(toState) && ctx.endCause == null && fromState != null) {
            if (TIMEOUT_HINT.equals(causeHint)) ctx.endCause = timeoutCause(fromState);
            else if (causeHint == null) ctx.endCause = AdCause.HUNG_MACHINE.name();
        }
        ctx.history.transition(ctx.historyName(), fromState, toState, causeHint);
    }

    /** A registry-forced failover (shutdown, persistence failure) stamps its reason as the end cause. */
    @Override
    protected final void onForcedFailover(String reason) {
        C ctx = getContext();
        if (ctx == null) return;
        if (ctx.endCause == null) ctx.endCause = AdCause.HUNG_MACHINE.name();
        ctx.history.note(ctx.historyName(), "forced failover: " + reason);
    }

    // ─────────────────────────────────────────────────────────────────
    // Base steps (named orchestration; domain work behind the hooks)
    // ─────────────────────────────────────────────────────────────────

    private void preprocessNow() {
        C ctx = getContext();
        if (ctx.createdAtMs == 0) ctx.createdAtMs = System.currentTimeMillis();
        PreprocessVerdict v;
        try {
            v = preprocess(ctx);
        } catch (RuntimeException e) {
            ctx.history.note(ctx.historyName(), "preprocessing threw: " + e);
            LOG.warn("[{}] preprocessing threw", getMachineId(), e);
            v = PreprocessVerdict.refuse(AdCause.INTERNAL_ERROR);
        }
        if (v == null) v = PreprocessVerdict.refuse(AdCause.INTERNAL_ERROR);
        if (v.pending()) return;                       // the answer re-enters by id as a Preprocessed event
        publishEvent(new Preprocessed(v.ok(), v.cause()));
    }

    private void recordRefusal(Preprocessed p) {
        C ctx = getContext();
        if (ctx.endCause == null) ctx.endCause = p.cause() == null ? AdCause.INTERNAL_ERROR.name() : p.cause();
    }

    private void admit() {
        C ctx = getContext();
        AdmissionVerdict v;
        try {
            v = runAdmission(ctx);
        } catch (RuntimeException e) {
            ctx.history.note(ctx.historyName(), "admission threw: " + e);
            LOG.warn("[{}] admission threw", getMachineId(), e);
            v = AdmissionVerdict.reject(AdCause.INTERNAL_ERROR.name());
        }
        if (!v.accepted() && ctx.endCause == null) ctx.endCause = v.rejectCause();
        publishEvent(new AdmissionDecided(v.accepted(), v.rejectCause()));
    }

    private void firstSpawn() {
        C ctx = getContext();
        ctx.attempts = 1;
        spawnChildren(resolver, ctx);
    }

    private void grantArrived(SignalingDone done) { onSignalingDone(getContext(), done.grant()); }

    private void progress(SignalingProgress p) { onSignalingProgress(getContext(), p.phase()); }

    private void signalingFailed(SignalingFailed f) {
        C ctx = getContext();
        boolean retry;
        try { retry = nextAttempt(ctx, f.cause()); }
        catch (RuntimeException e) {
            ctx.history.note(ctx.historyName(), "nextAttempt threw: " + e);
            LOG.warn("[{}] nextAttempt threw", getMachineId(), e);
            retry = false;
        }
        if (retry) {
            ctx.attempts++;
            ctx.history.note(ctx.historyName(), "retry attempt " + ctx.attempts + " after: " + f.cause());
            try { cleanupBeforeRetry(resolver, ctx); }
            catch (RuntimeException e) {
                ctx.history.note(ctx.historyName(), "cleanupBeforeRetry threw: " + e);
                LOG.warn("[{}] cleanupBeforeRetry threw", getMachineId(), e);
            }
            spawnChildren(resolver, ctx);
            return;
        }
        if (ctx.endCause == null) ctx.endCause = f.cause();
        transitionTo(FAILED);
    }

    private void recordDeferral(SignalingDeferred d) {
        C ctx = getContext();
        if (ctx.endCause == null) ctx.endCause = d.cause();
    }

    private void abortPreActive(ServiceEnd e) {
        C ctx = getContext();
        if (ctx.endCause == null) ctx.endCause = e.cause();
        transitionTo(FAILED);
    }

    private void activate() {
        C ctx = getContext();
        ctx.activatedAtMs = System.currentTimeMillis();
        onActive(ctx);
    }

    private void recordEnd(ServiceEnd e) {
        C ctx = getContext();
        if (ctx.endCause == null) ctx.endCause = e.cause();
    }

    private void teardown() {
        C ctx = getContext();
        ctx.tornDown = true;
        try { onTeardown(ctx); }
        catch (RuntimeException e) {
            ctx.history.note(ctx.historyName(), "teardown threw: " + e);
            LOG.warn("[{}] teardown threw", getMachineId(), e);
        }
        if (settlesAsync()) publishEvent(new SettleRequest());
        else transitionTo(succeededNow() ? SUCCEEDED : FAILED);
    }

    private boolean settleDecision(Settled s) {
        C ctx = getContext();
        try { onSettled(ctx, s.totals()); }
        catch (RuntimeException e) {
            ctx.history.note(ctx.historyName(), "onSettled threw: " + e);
            LOG.warn("[{}] onSettled threw", getMachineId(), e);
        }
        return succeededNow();
    }

    private boolean succeededNow() {
        C ctx = getContext();
        try { return sessionSucceeded(ctx); }
        catch (RuntimeException e) {
            ctx.history.note(ctx.historyName(), "sessionSucceeded threw: " + e);
            LOG.warn("[{}] sessionSucceeded threw", getMachineId(), e);
            return ctx.activatedAtMs > 0;
        }
    }

    /**
     * Terminal close — EVERY outcome lands here; the domain's {@link #onEnded} (the CDR) and the SDR are unconditional.
     * Idempotent (a re-entered terminal writes nothing twice).
     */
    private void close(String outcome) {
        C ctx = getContext();
        if (ctx == null) return;                      // forced failover raced a reset — nothing to record
        if (ctx.outcome != null) return;
        ctx.outcome = outcome;
        ctx.endedAtMs = System.currentTimeMillis();
        if (ctx.endCause == null) ctx.endCause = SUCCEEDED.equals(outcome) ? AdCause.NORMAL_CLEARING.name() : AdCause.HUNG_MACHINE.name();
        if (ctx.activatedAtMs > 0 && !ctx.tornDown) {   // dead-man path: TEARING_DOWN never ran
            ctx.tornDown = true;
            try { onTeardown(ctx); }
            catch (RuntimeException e) {
                ctx.history.note(ctx.historyName(), "backstop teardown threw: " + e);
                LOG.warn("[{}] backstop teardown threw", getMachineId(), e);
            }
        }
        try { onEnded(ctx, outcome); }
        catch (RuntimeException e) {
            ctx.history.note(ctx.historyName(), "onEnded threw: " + e);
            LOG.warn("[{}] onEnded threw", getMachineId(), e);
        }
        Object domainPayload;
        try {
            domainPayload = buildSdr(ctx, outcome);
        } catch (RuntimeException e) {
            LOG.error("[{}] buildSdr threw for session {} — shipping fallback SDR without domain payload: {}", getMachineId(), ctx.sessionKey, e.toString());
            ctx.history.note(ctx.historyName(), "buildSdr threw: " + e);
            domainPayload = null;
        }
        SdrRecord record = new SdrRecord(ctx.sessionKey, outcome, ctx.endCause, ctx.createdAtMs, ctx.activatedAtMs, ctx.endedAtMs,
            ctx.attempts, domainPayload, ctx.history.snapshot(), ctx.history.droppedCount());
        try {
            sdrSink().write(record);
        } catch (RuntimeException e) {
            LOG.error("[{}] SDR SINK WRITE FAILED for session {} outcome={} endCause={} — record lost: {}",
                getMachineId(), ctx.sessionKey, outcome, ctx.endCause, e.toString());
        }
    }
}
