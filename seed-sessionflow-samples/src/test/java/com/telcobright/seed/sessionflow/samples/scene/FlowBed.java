package com.telcobright.seed.sessionflow.samples.scene;

import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.internal.BudgetChild;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.persistence.PersistenceMode;
import com.telcobright.statewalk.persistence.PersistenceProvider;
import com.telcobright.statewalk.registry.MachineSpec;
import com.telcobright.statewalk.registry.StatemachineRegistry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One sample on the base, end to end: the application's {@link RoutesphereFlow} run by the base's {@link RoutesphereEngine} (statewalk's
 * SessionSupervisor + the lane's signaling machine + the budget child when the flow renews), the lane's fake channel with its decoder,
 * optionally a store. Keeps every supervisor context the kit made, so a test reads a session after its machines went back to the pools.
 */
public final class FlowBed<C extends RoutesphereContext, O, I> implements AutoCloseable {

    public final RoutesphereFlow<C> flow;
    public final TestChannel<O, I> channel;
    public final List<C> contexts = new CopyOnWriteArrayList<>();
    public final RoutesphereEngine<C> engine;
    private final String signalingType;
    private final int supervisorsAtStart, signalingAtStart, budgetsAtStart;

    public FlowBed(RoutesphereFlow<C> flow, Supplier<C> contexts, MachineSpec<?> signaling, TestChannel<O, I> channel,
                   BiFunction<String, I, Delivery> decoder) {
        this(flow, contexts, signaling, channel, decoder, null);
    }

    /** With a store: {@code PersistenceMode.RESUME} — built, the node restores every unfinished session before its channel opens. */
    public FlowBed(RoutesphereFlow<C> flow, Supplier<C> contexts, MachineSpec<?> signaling, TestChannel<O, I> channel,
                   BiFunction<String, I, Delivery> decoder, PersistenceProvider store) {
        this.flow = flow;
        this.channel = channel;
        this.signalingType = signaling.name();
        RoutesphereEngine.Builder<C> b = RoutesphereEngine.of(flow, () -> keep(contexts.get())).signaling(signaling).channel(channel, decoder);
        if (store != null) b.persistence(PersistenceMode.RESUME, () -> store);
        this.engine = b.start();
        this.supervisorsAtStart = registry().poolStatistics(flow.name()).available();
        this.signalingAtStart = registry().poolStatistics(signalingType).available();
        this.budgetsAtStart = flow.kit().settings().renews() ? registry().poolStatistics(BudgetChild.TYPE).available() : -1;
    }

    private C keep(C c) { contexts.add(c); return c; }

    public StatemachineRegistry<C> registry() { return engine.registry(); }

    /** One frame in; wait until the session is quiet. */
    public void frame(String id, I frame) throws Exception {
        channel.inject(id, frame).toCompletableFuture().get(5, SECONDS);
        quiet(id);
    }

    public void quiet(String id) throws Exception {
        assertTrue(registry().awaitSettled(id, 5, SECONDS), "settled");
        assertTrue(registry().awaitIdle(5, SECONDS), "idle");
    }

    /** Wait for a timer (or the budget child) to end the session. */
    public void awaitGone(String id, long withinMs) throws Exception {
        long until = System.currentTimeMillis() + withinMs;
        while (registry().hasAny(id) && System.currentTimeMillis() < until) Thread.sleep(10);
        quiet(id);
    }

    /** "supervisor/signaling"; a machine that is not live shows as "-". */
    public String states(String id) {
        String s = registry().supervisorStateOf(id);
        String c = registry().childStateOf(id, signalingType);
        return (s == null ? "-" : s) + "/" + (c == null ? "-" : c);
    }

    /** What the lane's machine sent on the wire for one session, in order. */
    public List<String> wire(String id) {
        return channel.sends.stream().filter(s -> s.requestId().equals(id)).map(s -> String.valueOf(s.command())).toList();
    }

    /** The context of the session with this id (the last one the kit made for it). */
    public C session(String id) {
        for (int i = contexts.size() - 1; i >= 0; i--) {
            C c = contexts.get(i);
            if (c.facts != null && id.equals(c.facts.sessionId())) return c;
        }
        throw new AssertionError("no session " + id);
    }

    /** The session ended and every machine of it — the supervisor, the signaling machine, the budget child — is back in its pool. */
    public void assertBackInThePools(String id) {
        assertNull(registry().supervisorStateOf(id), "the supervisor ended");
        assertNull(registry().childStateOf(id, signalingType), "the signaling machine ended");
        assertEquals(0, registry().activeCellCount(), "nothing live");
        assertEquals(supervisorsAtStart, registry().poolStatistics(flow.name()).available(), "the supervisor is back in its pool");
        assertEquals(signalingAtStart, registry().poolStatistics(signalingType).available(), "the signaling machine is back in its pool");
        if (budgetsAtStart >= 0) assertEquals(budgetsAtStart, registry().poolStatistics(BudgetChild.TYPE).available(), "the budget child is back in its pool");
    }

    @Override public void close() { engine.close(); }
}
