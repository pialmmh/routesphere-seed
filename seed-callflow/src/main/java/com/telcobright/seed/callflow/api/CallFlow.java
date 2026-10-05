package com.telcobright.seed.callflow.api;

import com.telcobright.seed.callflow.dependencies.CallFlowKit;
import com.telcobright.seed.callflow.internal.ChannelSlots;
import com.telcobright.seed.callflow.internal.FlowCounters;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.session.AdmissionVerdict;

import java.util.function.Supplier;

/**
 * The base call processing pipeline: the ONE flow every application's call runs — a voice call, an SMS, an ad view.
 * An application extends this class and says only its own steps ({@link CallFlowSteps}); everything below is the same
 * for all of them and cannot be overridden.
 *
 * <pre>
 *   PREPROCESSING   resolve the tenant → build the task → select the candidates
 *   ADMITTING       per candidate: identify the entry partner
 *                                  → the tenant chain, leaf to root: check, authorize, rate, RESERVE
 *                                  → resolve the route → confirm          (a refusal gives every reserve back)
 *   ADMITTED        start the signaling            (progress — ringing, early media — is a stay; a failed attempt may be retried)
 *   ACTIVE          the service runs               (a long call renews its reserve)
 *   TEARING_DOWN    stop the service → SETTLE every tier
 *   the end         settle if not yet settled → free the slot → publish ONE CDR message, a record per tier
 * </pre>
 *
 * The switch never writes a CDR or a summary table: it publishes the call's record, billing-core writes it and
 * summary-service sums it. Money moves only through the ledger's three verbs: reserve, settle, release.
 *
 * <p>One flow object serves every call of its application, on many threads. It holds no call: a call's state is its
 * context, and the machine that drives a call comes from a pool ({@link CallFlowEngine}).
 *
 * @param <C> the application's context
 */
public abstract class CallFlow<C extends CallFlowContext> extends CallFlowSteps<C> {

    private final ChannelSlots slots = new ChannelSlots();
    private final FlowCounters counters = new FlowCounters();
    private final AdmissionChain<C> admission;
    private final CallSettlement<C> settlement;
    private final CallCdr<C> cdr;

    protected CallFlow(CallFlowKit kit) {
        super(kit);
        this.admission = new AdmissionChain<>(this);
        this.settlement = new CallSettlement<>(this);
        this.cdr = new CallCdr<>(this);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // PREPROCESSING
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** The cause the call is refused with before admission, or null: the task and its candidates are ready. */
    public final String preprocess(C ctx) {
        try {
            stampStart(ctx);
            String refusal = step(ctx, "RESOLVE_TENANT", () -> resolveTenant(ctx));
            if (refusal == null) refusal = step(ctx, "BUILD_TASK", () -> buildTask(ctx));
            if (refusal == null) refusal = step(ctx, "SELECT_CANDIDATES", () -> selectCandidates(ctx));
            return refusal;
        } catch (RuntimeException e) {
            return internalError(ctx, "preprocessing", e);
        }
    }

    private void stampStart(C ctx) {
        if (ctx.createdAtMs == 0) ctx.createdAtMs = kit.clock().millis();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // ADMITTING
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Try the candidates in order; the first that every tier admits is the call. For each candidate: identify the entry
     * partner → the tenant chain leaf to root (check, slot, authorize, root rules, rate, reserve) → the route → confirm.
     * {@code SIMULATE} walks and rates the same way and moves nothing.
     */
    public final AdmissionVerdict admit(C ctx, StepMode mode) { return admission.admitFirstCandidate(ctx, mode); }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // ADMITTED · ACTIVE
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    public final void signal(C ctx, CallMachine machine) {
        ctx.attempts = 1;
        startSignaling(ctx, machine);
    }

    /** The signaling reported progress (C11): the first report is the PDD; the call stays ADMITTED, its deadline untouched. */
    public final void progress(C ctx, String phase) {
        if (ctx.progressAtMs == 0) ctx.progressAtMs = kit.clock().millis();
        guarded(ctx, "onProgress", () -> onProgress(ctx, phase));
    }

    /**
     * The signaling failed before the answer (C12). The attempt is recorded, then the application's rule decides
     * ({@link CallFlowSteps#nextAttempt}: by default the v1 table on the route plan). True = the attempt's children are retired and the
     * signaling starts again on the plan's hop — on the same admission: nothing is re-admitted or re-reserved, and the base's balance
     * child lives on. False = the call fails with that cause.
     */
    public final boolean retry(C ctx, String failureCause, CallMachine machine) {
        recordFailedAttempt(ctx, failureCause);
        if (!safely(ctx, "nextAttempt", () -> nextAttempt(ctx, failureCause), false)) return false;
        ctx.attempts++;
        ctx.history.note(name(), "attempt " + ctx.attempts + " after: " + failureCause + (ctx.routePlan == null ? "" : " — " + ctx.routePlan));
        machine.retireChildren();
        startSignaling(ctx, machine);
        return true;
    }

    /** Every failed attempt is recorded before the policy sees it, as v1 did: on the plan (which hop, what cause, when) and in the history. */
    private void recordFailedAttempt(C ctx, String failureCause) {
        if (ctx.routePlan != null) ctx.routePlan.record(failureCause, kit.clock().millis());
        ctx.history.note(name(), "attempt " + ctx.attempts + " failed: " + failureCause);
    }

    public final void answered(C ctx, Object grant) {
        if (ctx.answeredAtMs == 0) ctx.answeredAtMs = kit.clock().millis();
        guarded(ctx, "onAnswered", () -> onAnswered(ctx, grant));
    }

    public final void active(C ctx, CallMachine machine) {
        ctx.activatedAtMs = kit.clock().millis();
        guarded(ctx, "onActive", () -> onActive(ctx, machine));
    }

    /**
     * A long call renews its reserve at every tier. Null = the call goes on. Else the cause to end it with: a tier
     * cannot pay the next window. A ledger FAULT never cuts a call: the settlement reconciles when the call ends.
     */
    public final String reserveNextWindow(C ctx) { return admission.reserveNextWindow(ctx); }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // TEARING_DOWN · the end
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** TEARING_DOWN: stop the service, then settle every tier. */
    public final void teardown(C ctx, CallMachine machine) {
        guarded(ctx, "STOP_SERVICE", () -> stopService(ctx, machine));
        guarded(ctx, "SETTLE", () -> settle(ctx));
    }

    /**
     * Every tier pays what the settle rule says, and the rest of its reserve goes back. It runs exactly once per call,
     * on every end path — a normal end, a refusal, a deadline, a killed machine: no path refunds by a rule of its own.
     */
    public final void settle(C ctx) { settlement.settle(ctx); }

    /**
     * The end of every call, whatever its outcome: the service is stopped, every tier is settled, the slot is free, the
     * CDR is published, the application closes its own. No step's failure stops the next.
     */
    public final void end(C ctx, String outcome, CallMachine machine) {
        guarded(ctx, "STOP_SERVICE", () -> stopService(ctx, machine));
        guarded(ctx, "SETTLE", () -> settle(ctx));
        guarded(ctx, "RELEASE_SLOT", () -> slots.release(ctx.sessionKey));
        guarded(ctx, "PUBLISH_CDR", () -> cdr.publish(ctx, outcome));
        guarded(ctx, "ON_ENDED", () -> onEnded(ctx, outcome));
        counters.ended.incrementAndGet();
    }

    private void stopService(C ctx, CallMachine machine) {
        if (ctx.tornDown) return;
        ctx.tornDown = true;
        onTeardown(ctx, machine);
    }

    public final boolean succeededNow(C ctx) {
        return safely(ctx, "succeeded", () -> succeeded(ctx), ctx.activatedAtMs > 0);
    }

    public final String causeOfTimeout(String state) {
        try {
            String cause = timeoutCause(state);
            return cause != null ? cause : CallCause.INTERNAL_ERROR;
        } catch (RuntimeException e) {
            return CallCause.INTERNAL_ERROR;
        }
    }

    public final Object sdrOf(C ctx, String outcome) {
        return safely(ctx, "buildSdr", () -> buildSdr(ctx, outcome), null);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // The dry run
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** What this call would come to, with no side effect: nothing reserved, no slot, no machine, no CDR. */
    public final DryRun simulate(C ctx) {
        ctx.traced = true;
        String refusal = preprocess(ctx);
        if (refusal != null) return DryRun.of(false, refusal, ctx);
        AdmissionVerdict verdict = admit(ctx, StepMode.SIMULATE);
        return DryRun.of(verdict.accepted(), verdict.rejectCause(), ctx);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // For the base's own collaborators
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    final ChannelSlots slots() { return slots; }

    final FlowCounters counters() { return counters; }

    /** Run one step. With debug on (or in a dry run) the step is timed and written to the call's history and the log. */
    final <T> T step(C ctx, String stepName, Supplier<T> body) {
        boolean debug = kit.settings().debug();
        if (!ctx.traced && !debug) return body.get();
        long startedAt = System.nanoTime();
        T result = body.get();
        long micros = (System.nanoTime() - startedAt) / 1_000;
        Object shown = result == null ? "ok" : result;
        ctx.history.note(name(), stepName + " → " + shown + " (" + micros + " µs)");
        if (debug) log.info("[{}] {} | {} | {} | {} µs", name(), ctx.sessionKey, stepName, shown, micros);
        return result;
    }

    final String internalError(C ctx, String where, RuntimeException e) {
        ctx.history.note(name(), where + " threw: " + e);
        log.error("[{}] {} | {} threw — the call ends with INTERNAL_ERROR", name(), ctx.sessionKey, where, e);
        return CallCause.INTERNAL_ERROR;
    }

    /** Run a step of the application that must never break the call: a throw is logged and {@code whenItThrows} is the answer. */
    final <T> T safely(C ctx, String stepName, Supplier<T> body, T whenItThrows) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            ctx.history.note(name(), stepName + " threw: " + e);
            log.error("[{}] {} | {} threw", name(), ctx.sessionKey, stepName, e);
            return whenItThrows;
        }
    }

    private void guarded(C ctx, String stepName, Runnable body) {
        safely(ctx, stepName, () -> { body.run(); return null; }, null);
    }
}
