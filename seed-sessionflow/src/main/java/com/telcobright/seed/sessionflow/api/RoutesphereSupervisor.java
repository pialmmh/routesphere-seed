package com.telcobright.seed.sessionflow.api;

import com.telcobright.statewalk.registry.SupervisorSpec;
import com.telcobright.statewalk.session.SessionSupervisor;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * One application's supervisor class (R6; the owner: "each flow shall implement its own supervisor — CallSupervisor, AdSupervisor,
 * SmsSupervisor — all on statewalk's registry"): the lane's NAMED class — {@code public final class AdSupervisor extends
 * RoutesphereSupervisor<AdContext>} — and nothing else. Thin on purpose: statewalk's {@code SessionSupervisor} stays final (the states and
 * the money contract are the same in every lane, the 2026-10-05 ruling); the lane's answers are its flow's. The class holds the flow and
 * the contexts, names the lane ({@link #name()} = the flow's name: statewalk's table and the log lines) and makes statewalk's spec.
 * {@link RoutesphereEngine#of} takes it — the one door, so no lane runs without its class.
 *
 * @param <C> the application's context
 */
public abstract class RoutesphereSupervisor<C extends RoutesphereContext> {

    private final RoutesphereFlow<C> flow;
    private final Supplier<C> contexts;

    /** @param contexts a new context per session (the lane's {@code XContext::new}; a test bed's keeps them) */
    protected RoutesphereSupervisor(RoutesphereFlow<C> flow, Supplier<C> contexts) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    /** The lane's ONE name — its flow's: the registered supervisor, statewalk's table of the lane (§L.4), the log lines. */
    public final String name() { return flow.name(); }

    /** The lane's answers. */
    public final RoutesphereFlow<C> flow() { return flow; }

    /** statewalk's supervisor of this lane: the final SessionSupervisor, the flow its handler. */
    public final SupervisorSpec<C> spec() { return SessionSupervisor.spec(name(), contexts, flow); }

    @Override public String toString() { return getClass().getSimpleName() + "[" + name() + "]"; }
}
