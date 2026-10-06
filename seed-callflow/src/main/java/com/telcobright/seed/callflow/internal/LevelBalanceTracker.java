package com.telcobright.seed.callflow.internal;

import com.telcobright.seed.callflow.api.CallFlow;
import com.telcobright.seed.callflow.api.CallFlowContext;
import com.telcobright.seed.callflow.dependencies.CallFlowSettings;
import com.telcobright.seed.callflow.publishes.BudgetStart;
import com.telcobright.statewalk.machine.Machine;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SettleRequest;
import com.telcobright.statewalk.session.events.Settled;
import com.telcobright.statewalk.state.StateMap;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The balance child of one call — the call switch's {@code BalanceTracker} on the base. It holds the tiers' reserves from ADMITTED
 * on, renews them every reserve period while the call is ACTIVE (C14) and settles them when TEARING_DOWN asks (C16, C17), answering
 * the supervisor with the per-tier results (C18). It outlives every signaling attempt: a re-route retires the signaling child only.
 *
 * <pre>
 *   HOLDING ──BudgetStart──► [FIRST_RENEWAL] ──(the first tick, at the initial delay)──► RENEWING ──(one tick per period: every
 *      │                           │                                                    │   tier renews; the narrowest window decides)
 *      │                           │   the period: on · 0: cut now · less: WINDING_DOWN, the cut armed for when the money ends
 *      └───── SettleRequest ───────┴────────────────────────────────────────────────────┴────────► SETTLING ──Settled──► CLOSED
 * </pre>
 *
 * <p>FIRST_RENEWAL (B10) is entered only when the first renewal is not one period after the answer
 * ({@link CallFlowSettings#firstRenewalSec}): the call switch renews first at 58 s, "just under one unit so the renewal precedes its
 * expiry", then every 60 s. With no initial delay set the child goes straight to RENEWING, as before B10.
 *
 * <p>The renewal is the call switch's (C14): a tier that cannot hold a whole window may hold the remainder, and the call is then
 * cut at the moment that credit ends — not at the next tick — so the caller spends the last partial unit instead of being cut with
 * paid credit still in the package ({@code BalanceTracker.renewOrSignal} + {@code CallSupervisor.scheduleFinalCut}). Nothing is
 * settled here on a cut: the end runs the one settlement rule on the real talk time.
 *
 * <p>The settlement itself is the base's one rule ({@link CallFlow#settle}), exactly once per call: a call that never reaches this
 * child's SETTLING — a deadline, a kill — is settled by the supervisor's end with the same rule. Its context is the call's own.
 *
 * <p>Pooled: its fields are the flow and the period, final and shared.
 */
public final class LevelBalanceTracker<C extends CallFlowContext> extends Machine<C> {

    public static final String TYPE = "LevelBalanceTracker";
    static final String HOLDING = "HOLDING";
    static final String FIRST_RENEWAL = "FIRST_RENEWAL";
    static final String RENEWING = "RENEWING";
    static final String WINDING_DOWN = "WINDING_DOWN";
    static final String SETTLING = "SETTLING";
    static final String CLOSED = "CLOSED";

    private final CallFlow<C> flow;
    private final long periodSec;
    private final long firstRenewalSec;

    public LevelBalanceTracker(CallFlow<C> flow) {
        this.flow = flow;
        this.periodSec = flow.kit().settings().reservePeriodSec();
        this.firstRenewalSec = flow.kit().settings().firstRenewalSec();
    }

    @Override
    protected StateMap defineStates() {
        CallFlowSettings s = flow.kit().settings();
        long lifetimeSec = s.globalTimeoutSec();           // longer than the longest healthy call, by the settings' own rule
        StateMap.Builder.StateBuilder first = StateMap.builder()
            .initialState(HOLDING)

            .state(HOLDING)
                .interim()
                .timeout(lifetimeSec, TimeUnit.SECONDS, CLOSED)
                .stay(BudgetStart.class, (self, e) -> me(self).startCadence())
                .on(SettleRequest.class, SETTLING)

            .state(FIRST_RENEWAL)
                .interim();
        first = periodSec > 0
            ? first.timeoutStay(firstRenewalSec, TimeUnit.SECONDS, self -> me(self).firstRenewal())
            : first.timeout(lifetimeSec, TimeUnit.SECONDS, CLOSED);        // never entered: with no period there is no cadence
        StateMap.Builder.StateBuilder renewing = first
                .on(SettleRequest.class, SETTLING)

            .state(RENEWING)
                .interim();
        renewing = periodSec > 0
            ? renewing.timeoutStay(periodSec, TimeUnit.SECONDS, self -> me(self).renew())
            : renewing.timeout(lifetimeSec, TimeUnit.SECONDS, CLOSED);     // never entered: with no period there is no cadence
        return renewing
                .on(SettleRequest.class, SETTLING)

            .state(WINDING_DOWN)
                .interim()
                .timeout(lifetimeSec, TimeUnit.SECONDS, CLOSED)
                .on(SettleRequest.class, SETTLING)

            .state(SETTLING)
                .interim()
                .timeout(Math.max(1, s.timings().tearingDownSec()), TimeUnit.SECONDS, CLOSED)
                .onEntry(self -> me(self).settle())

            .state(CLOSED)
                .finalState()
                .timeout(1, TimeUnit.SECONDS, CLOSED)

            .build();
    }

    @SuppressWarnings("unchecked")
    private static <C extends CallFlowContext> LevelBalanceTracker<C> me(Object self) { return (LevelBalanceTracker<C>) self; }

    /**
     * ACTIVE: the cadence starts. The first renewal comes {@code firstRenewalSec} after the answer — one period unless an initial
     * delay is set (B10: the call switch's 58 s) — then one every period.
     */
    private void startCadence() {
        if (periodSec <= 0) return;
        transitionTo(firstRenewalSec == periodSec ? RENEWING : FIRST_RENEWAL);
    }

    /** B10 · The first tick: the same renewal as every other; when the cadence goes on, the next ticks come every period. */
    private void firstRenewal() {
        if (renew()) transitionTo(RENEWING);
    }

    /**
     * One tick of the cadence (C14): every tier renews; the narrowest window decides. The period = the cadence goes on. 0 = the call
     * is cut now. Less than the period = no more renewals; the cut is armed for the moment the money ends.
     *
     * @return true = the cadence goes on (the next tick decides); false = no more renewals, or nothing to renew
     */
    private boolean renew() {
        C ctx = getContext();
        if (ctx == null || ctx.reservesClosed) return false;
        double seconds = flow.renewReserves(ctx);
        if (seconds >= periodSec) return true;
        if (seconds <= 0) {
            cut(ctx, "a tier can pay nothing more");
            transitionTo(WINDING_DOWN);                         // no more renewals, whoever ends the call
            return false;
        }
        if (armFinalCut(ctx, seconds)) {
            transitionTo(WINDING_DOWN);
            return false;
        }
        return true;                                            // no timer to arm the cut: the next tick decides
    }

    /**
     * The money ended: the application says how the call ends ({@code cutForBalance}) — now, with the cause it names (the default:
     * BALANCE_EXHAUSTED), or by the wire after the application cut the service itself (the call switch: both legs killed; the hangup
     * FreeSWITCH reports ends the call and the settlement runs on the real talk time).
     */
    private void cut(C ctx, String why) {
        ctx.history.note(TYPE, "balance exhausted: " + why);
        String cause = flow.cutForBalanceNow(ctx);
        if (cause != null) publishEvent(new ServiceEnd(cause));
        else ctx.history.note(TYPE, "the application cut the service on the wire: the wire's end ends the call");
    }

    /** The call switch's final window: the cut comes when the credit ends, not at the next tick. False = no timer: the next tick decides. */
    private boolean armFinalCut(C ctx, double seconds) {
        MachineRegistryHandle timer = getRegistry();
        if (timer == null) {
            ctx.history.note(TYPE, String.format("final window of %.1f s, but no timer to arm its cut: the next tick decides", seconds));
            return false;
        }
        long delayMs = Math.max(0L, (long) (seconds * 1000.0));
        ctx.history.note(TYPE, String.format("final window: %.1f s of service left — the cut is armed", seconds));
        ctx.balanceCut = timer.schedule(getMachineId(), () -> finalCut(ctx), delayMs, TimeUnit.MILLISECONDS);
        return true;
    }

    /** The armed cut fires: only for the call it was armed for, and only while that call still runs. */
    private void finalCut(C ctx) {
        if (getContext() != ctx || ctx.reservesClosed || ctx.outcome != null) return;
        cut(ctx, "the final window ended");
    }

    private static void disarmFinalCut(CallFlowContext ctx) {
        ScheduledFuture<?> armed = ctx.balanceCut;
        if (armed == null) return;
        ctx.balanceCut = null;
        armed.cancel(false);
    }

    /** TEARING_DOWN asked (C16): the one settlement rule runs here, once; the supervisor gets the per-tier results (C17, C18). */
    private void settle() {
        C ctx = getContext();
        if (ctx != null) {
            disarmFinalCut(ctx);
            flow.settle(ctx);
            publishEvent(new Settled(ctx.settlements));
        }
        transitionTo(CLOSED);
    }
}
