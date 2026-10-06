package com.telcobright.seed.callflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.callflow.dependencies.CallFlowKit;
import com.telcobright.seed.callflow.internal.ChannelSlots;
import com.telcobright.seed.callflow.internal.FlowCounters;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.session.AdmissionVerdict;

import java.util.function.BooleanSupplier;
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

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // R1-6 · the calls in the air: every reserve ends in a record or goes back, a process death included
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The application hands the call to its user (an ad's start road hands the session to the phone): the call's line is written
     * to the journal of the calls in the air FIRST — on this thread, one append — then {@code theFact} is asked (the application's
     * own decision: is the call still there to hand over?).
     *
     * @return true = handed over. False = do not hand it over: its line could not be written (the cause is in the call's history;
     *         the caller refuses the call as a system fault and its reserves go back), or the fact said no (the call ended first;
     *         its line is done again at once — its own end publishes its record)
     */
    public final boolean handOver(C ctx, BooleanSupplier theFact) {
        if (!journalTheHandOver(ctx)) return false;
        if (theFact.getAsBoolean()) return true;
        kit.journal().done(ctx.sessionKey);
        return false;
    }

    private boolean journalTheHandOver(C ctx) {
        if (!kit.journal().keeps()) return true;
        try {
            kit.journal().handedOver(ctx.sessionKey, kit.clock().millis(), cdr.lostRecordsOf(ctx));
            return true;
        } catch (RuntimeException e) {
            counters.journalRefused.incrementAndGet();
            ctx.history.note(name(), "its line in the journal of the calls in the air (" + kit.journal().where() + ") could not be written: " + e.getMessage()
                + " — not handed over, refused as a system fault; its reserves go back");
            return false;
        }
    }

    /**
     * What the switch learned of a call in the air since its hand-over: its answer, the seconds it is billed for so far. The next
     * start's record of the call says them if the process dies before the call's end. Never fails the call.
     */
    public final void noteInTheAir(String callId, long answeredAtMs, double billedSec) {
        try {
            kit.journal().noted(callId, kit.clock().millis(), answeredAtMs, billedSec);
        } catch (RuntimeException e) {
            log.warn("[{}] {} | the journal of the calls in the air did not take what was learned of the call (its record after a death would say less): {}",
                name(), callId, e.toString());
        }
    }

    /**
     * Before the first call of a start (the engine asks it): the record of every call a stopped process left in the air is published
     * — handed over, ended {@link CallCause#LOST_AT_RESTART}, every tier charged what it reserved. One WARN with the count and the sums.
     */
    public final void publishWhatWasLeftInTheAir() {
        CallCdr.LeftInTheAir left = cdr.publishLeftovers();
        if (left.calls() == 0) return;
        log.warn("[{}] the start published the records of {} call(s) a stopped process left in the air ({}): each ended {}, every tier charged what it reserved — {} in money and {} in units over every tier",
            name(), left.calls(), kit.journal().where(), CallCause.LOST_AT_RESTART, left.money().toPlainString(), left.units().toPlainString());
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
     * ACTIVE, every reserve period (C14): every tier renews its window; the narrowest answer, in seconds, is how long the call may
     * still run. The period = the call goes on; 0 = a tier can pay nothing more; between = a partial window, to be cut when it ends.
     * A tier that answers 0 ends the asking (the rest are not renewed for a call that is over). A hook that throws never cuts a
     * call. Asked by the balance child, on the call's own bus, so a renewal never runs beside the settlement of the same call.
     */
    public final double renewReserves(C ctx) {
        double period = kit.settings().reservePeriodSec();
        if (ctx.reservesClosed) return period;
        double narrowest = period;
        for (LevelAdmission level : ctx.levels) {
            narrowest = Math.min(narrowest, safely(ctx, "renewWindowSeconds", () -> renewWindowSeconds(ctx, level), period));
            if (narrowest <= 0) break;
        }
        return narrowest;
    }

    @Override
    final double renewThroughLedger(C ctx, LevelAdmission level) { return admission.renewThroughLedger(ctx, level); }

    /**
     * The money ended (C14), asked by the balance child: the cause to end the call with now, or null when the application cut the service
     * on the wire and its end will end the call ({@link CallFlowSteps#cutForBalance}). A hook that throws ends the call now, as the default.
     */
    public final String cutForBalanceNow(C ctx) {
        return safely(ctx, "cutForBalance", () -> cutForBalance(ctx), CallCause.BALANCE_EXHAUSTED);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // TEARING_DOWN · the end
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * TEARING_DOWN: stop the service, then settle every tier — here, or in the balance child when the application settles
     * asynchronously (the supervisor then asks the child with {@code SettleRequest} and takes its {@code Settled}).
     */
    public final void teardown(C ctx, CallMachine machine) {
        guarded(ctx, "STOP_SERVICE", () -> stopService(ctx, machine));
        if (!balanceChildSettles()) guarded(ctx, "SETTLE", () -> settle(ctx));
    }

    /** True = the balance child settles this call ({@link CallFlowSteps#settlesAsync}): TEARING_DOWN asks it and waits for its answer. */
    public final boolean balanceChildSettles() { return settlesAsync(); }

    /** True = this call has a balance child: it settles asynchronously, or it renews its reserve every period. */
    public final boolean usesBalanceChild() { return balanceChildSettles() || kit.settings().reservePeriodSec() > 0; }

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
