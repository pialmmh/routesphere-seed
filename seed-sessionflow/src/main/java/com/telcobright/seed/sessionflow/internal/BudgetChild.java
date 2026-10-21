package com.telcobright.seed.sessionflow.internal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.statewalk.machine.Machine;
import com.telcobright.statewalk.registry.MachineSpec;
import com.telcobright.statewalk.session.events.BudgetExhausted;
import com.telcobright.statewalk.session.events.SessionActive;
import com.telcobright.statewalk.state.StateMap;

import java.util.concurrent.TimeUnit;

/**
 * The budget child of one session (§E) — the call switch's {@code BalanceTracker}, the seed's {@code LevelBalanceTracker}, as a statewalk
 * 4.0 {@link MachineSpec}: spawned in INIT ({@code prepare}) when the flow renews, resting until statewalk tells it the session is ACTIVE
 * ({@code SessionActive}, G-1 — never earlier), then renewing every tier's reserve once per period (C14); when the money ends it publishes
 * {@code BudgetExhausted} and the supervisor sends {@code Stop(BALANCE_EXHAUSTED)} down. The settlement is never here: the final state
 * settles every tier on the real service time. The registry resets it with the session and returns it to its pool.
 *
 * <pre>
 *   IDLE ─(spawned)─► HOLDING ─SessionActive─► [FIRST_RENEWAL] ─(the first tick, at the initial delay)─► RENEWING ─(a tick per period)
 *                                                    │ the period: on · 0: BudgetExhausted now · less: the cut armed for when the money ends
 *                                                    └──────────────────────────────────────────────────────────────► WINDING_DOWN
 * </pre>
 *
 * <p>Its context is its own, small and persisted ({@link BudgetContext}); the session's context is reached live (a restored child finds
 * it again through the kit). §L.2: a persisting lane renews at most once a minute — every tick rewrites the session's row.
 */
public final class BudgetChild {

    public static final String TYPE = "Budget";
    public static final String HOLDING = "HOLDING";
    public static final String FIRST_RENEWAL = "FIRST_RENEWAL";
    public static final String RENEWING = "RENEWING";
    public static final String WINDING_DOWN = "WINDING_DOWN";
    public static final String CLOSED = "CLOSED";

    private BudgetChild() {}

    /** The budget child's own context: which session, how far it metered. Persisted with the session (a Jackson POJO). */
    public static final class BudgetContext {
        public String sessionId;
        public long activeAtMs;
        public int ticks;
        public boolean exhausted;
        public double finalWindowSec;
        /** The session's context, live (never persisted): a restored child finds it again by {@link #sessionId}. */
        @JsonIgnore public transient volatile RoutesphereContext session;

        public BudgetContext() {}
    }

    /** The context the base spawns the child with: the session's id and the session itself. */
    public static BudgetContext contextFor(RoutesphereContext session) {
        BudgetContext b = new BudgetContext();
        b.sessionId = session.sessionId();
        b.session = session;
        return b;
    }

    /** The child's spec for this flow's settings (the engine registers it when the flow renews). */
    public static <C extends RoutesphereContext> MachineSpec<BudgetContext> spec(RoutesphereFlow<C> flow) {
        RoutesphereSettings s = flow.kit().settings();
        String firstMove = s.firstRenewalSec() == s.reservePeriodSec() ? RENEWING : FIRST_RENEWAL;
        return MachineSpec.<BudgetContext>builder()
            .name(TYPE)
            .contextFactory(BudgetContext::new)
            .stateMap(StateMap.builder()
                .initialState(StateMap.IDLE)
                .state(StateMap.IDLE).timeout(0)
                    .onEntry(self -> machine(self).transitionTo(HOLDING))                       // spawned: rests until the answer
                .state(HOLDING).interim().timeout(0)                                          // the session's lifetime governs
                    .on(SessionActive.class, firstMove, null, (self, e) -> ctx(self).activeAtMs = ((SessionActive) e).atMs())
                .state(FIRST_RENEWAL).interim().timeoutStay(Math.max(1, s.firstRenewalSec()), TimeUnit.SECONDS, self -> tick(flow, self, true))
                .state(RENEWING).interim().timeoutStay(Math.max(1, s.reservePeriodSec()), TimeUnit.SECONDS, self -> tick(flow, self, false))
                .state(WINDING_DOWN).interim().timeout(0)                                     // no more renewals; the session ends it
                .state(CLOSED).finalState().timeout(1, TimeUnit.SECONDS, CLOSED)
                .build())
            .build();
    }

    /**
     * One tick of the cadence (C14): every tier renews; the narrowest window decides. The period = on (the first tick moves to RENEWING);
     * 0 = the money ended now; less = the final window: the cut armed for the moment it ends.
     */
    static <C extends RoutesphereContext> void tick(RoutesphereFlow<C> flow, Object self, boolean first) {
        BudgetContext b = ctx(self);
        C session = sessionOf(flow, b);
        if (session == null || session.reservesClosed) return;
        b.ticks++;
        double seconds = flow.renewReserves(session);
        if (seconds >= flow.kit().settings().reservePeriodSec()) { if (first) machine(self).transitionTo(RENEWING); return; }
        if (seconds <= 0) { exhausted(self, b, "a tier can pay nothing more"); return; }
        armTheFinalCut(self, b, seconds);
    }

    /** The money ended: BudgetExhausted up (the supervisor sends Stop down), no more renewals. */
    private static void exhausted(Object self, BudgetContext b, String what) {
        if (b.exhausted) return;
        b.exhausted = true;
        Machine<?> m = machine(self);
        m.publishEvent(new BudgetExhausted(System.currentTimeMillis(), what));
        if (!WINDING_DOWN.equals(m.getCurrentState())) m.transitionTo(WINDING_DOWN);
    }

    /** The call switch's final window: the cut comes when the credit ends, not at the next tick. */
    private static void armTheFinalCut(Object self, BudgetContext b, double seconds) {
        Machine<?> m = machine(self);
        b.finalWindowSec = seconds;
        long delayMs = Math.max(0L, (long) (seconds * 1000.0));
        m.getRegistry().schedule(m.getMachineId(), () -> exhausted(self, b, "the final window ended"), delayMs, TimeUnit.MILLISECONDS);
        m.transitionTo(WINDING_DOWN);
    }

    @SuppressWarnings("unchecked")
    private static <C extends RoutesphereContext> C sessionOf(RoutesphereFlow<C> flow, BudgetContext b) {
        if (b.session == null && b.sessionId != null) b.session = flow.kit().sessionOf(b.sessionId).orElse(null);
        return (C) b.session;
    }

    @SuppressWarnings("unchecked")
    private static Machine<BudgetContext> machine(Object self) { return (Machine<BudgetContext>) self; }

    private static BudgetContext ctx(Object self) { return machine(self).getContext(); }
}
