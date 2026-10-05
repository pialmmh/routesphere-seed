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

import java.util.concurrent.TimeUnit;

/**
 * The balance child of one call — the call switch's {@code BalanceTracker} on the base. It holds the tiers' reserves from ADMITTED
 * on, renews them every reserve period while the call is ACTIVE (C14) and settles them when TEARING_DOWN asks (C16, C17), answering
 * the supervisor with the per-tier results (C18). It outlives every signaling attempt: a re-route retires the signaling child only.
 *
 * <pre>
 *   HOLDING ──BudgetStart──► RENEWING ──(one tick per period: every tier renews; a tier that cannot pay ends the call)
 *      │                        │
 *      └───── SettleRequest ────┴──► SETTLING ──Settled──► CLOSED
 * </pre>
 *
 * <p>The settlement itself is the base's one rule ({@link CallFlow#settle}), exactly once per call: a call that never reaches this
 * child's SETTLING — a deadline, a kill — is settled by the supervisor's end with the same rule. Its context is the call's own.
 *
 * <p>Pooled: its only field is the flow, final and shared.
 */
public final class LevelBalanceTracker<C extends CallFlowContext> extends Machine<C> {

    public static final String TYPE = "LevelBalanceTracker";
    static final String HOLDING = "HOLDING";
    static final String RENEWING = "RENEWING";
    static final String SETTLING = "SETTLING";
    static final String CLOSED = "CLOSED";

    private final CallFlow<C> flow;
    private final long periodSec;

    public LevelBalanceTracker(CallFlow<C> flow) {
        this.flow = flow;
        this.periodSec = flow.kit().settings().reservePeriodSec();
    }

    @Override
    protected StateMap defineStates() {
        CallFlowSettings s = flow.kit().settings();
        long lifetimeSec = s.globalTimeoutSec();           // longer than the longest healthy call, by the settings' own rule
        StateMap.Builder.StateBuilder renewing = StateMap.builder()
            .initialState(HOLDING)

            .state(HOLDING)
                .interim()
                .timeout(lifetimeSec, TimeUnit.SECONDS, CLOSED)
                .stay(BudgetStart.class, (self, e) -> me(self).startCadence())
                .on(SettleRequest.class, SETTLING)

            .state(RENEWING)
                .interim();
        renewing = periodSec > 0
            ? renewing.timeoutStay(periodSec, TimeUnit.SECONDS, self -> me(self).renew())
            : renewing.timeout(lifetimeSec, TimeUnit.SECONDS, CLOSED);     // never entered: with no period there is no cadence
        return renewing
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

    /** ACTIVE: the cadence starts — the first renewal comes one period after the answer, as the call switch's did. */
    private void startCadence() {
        if (periodSec > 0) transitionTo(RENEWING);
    }

    /** One tick of the cadence (C14): every tier renews its window; a tier that cannot pay the next one ends the call. */
    private void renew() {
        C ctx = getContext();
        if (ctx == null || ctx.reservesClosed) return;
        String cause = flow.reserveNextWindow(ctx);
        if (cause == null) return;
        ctx.history.note(TYPE, "the next window could not be held: " + cause);
        publishEvent(new ServiceEnd(cause));
    }

    /** TEARING_DOWN asked (C16): the one settlement rule runs here, once; the supervisor gets the per-tier results (C17, C18). */
    private void settle() {
        C ctx = getContext();
        if (ctx != null) {
            flow.settle(ctx);
            publishEvent(new Settled(ctx.settlements));
        }
        transitionTo(CLOSED);
    }
}
