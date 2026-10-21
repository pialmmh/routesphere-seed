package com.telcobright.seed.sessionflow.samples.bed;

import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.registry.MachineSpec;
import com.telcobright.statewalk.registry.StatemachineRegistry;
import com.telcobright.statewalk.session.SessionHandler;
import com.telcobright.statewalk.session.SessionSupervisor;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One registry = one lane on a test bed: the library's {@link SessionSupervisor} under a handler, the lane's signaling machine, its fake
 * channel with its decoder — the statewalk SIP example's bed, for any machine. The registry's builder is the first check: it refuses a
 * signaling spec with a road that publishes nothing up, a timed state with no {@code TimeoutEvent} rule, or a live state that does not
 * handle both {@code Reject} and {@code Stop}.
 *
 * @param <O> what the machine sends on the wire
 * @param <I> the raw frame the channel hands in
 */
public final class Bed<O, I> implements AutoCloseable {

    public static final String SUPERVISOR = "BedSupervisor";

    public final Script script;
    public final TestChannel<O, I> channel;
    public final StatemachineRegistry<BedContext> reg;
    private final String signalingType;
    private final int supervisorsAtStart;
    private final int signalingAtStart;

    /** The minimal handler (the {@link ScriptedHandler}) over {@code script}. */
    public <X> Bed(String name, Script script, MachineSpec<X> signaling, TestChannel<O, I> channel, BiFunction<String, I, Delivery> decoder) {
        this(name, script, new ScriptedHandler(script), signaling, channel, decoder);
    }

    public <X> Bed(String name, Script script, SessionHandler<BedContext> handler, MachineSpec<X> signaling, TestChannel<O, I> channel,
                   BiFunction<String, I, Delivery> decoder) {
        this.script = script;
        this.channel = channel;
        this.signalingType = signaling.name();
        this.reg = StatemachineRegistry.<BedContext>builder(name)
            .supervisor(SessionSupervisor.spec(SUPERVISOR, script::newContext, handler), 8)
            .signaling(signaling, 8)
            .channel(channel, decoder)
            .globalTimeout(1, TimeUnit.HOURS, SessionSupervisor.FAILED)
            .threads(2)
            .build();
        this.supervisorsAtStart = reg.poolStatistics(SUPERVISOR).available();
        this.signalingAtStart = reg.poolStatistics(signalingType).available();
    }

    /** One frame in; wait until the session is quiet. */
    public void frame(String id, I frame) throws Exception {
        channel.inject(id, frame).toCompletableFuture().get(5, SECONDS);
        quiet(id);
    }

    public void quiet(String id) throws Exception {
        assertTrue(reg.awaitSettled(id, 5, SECONDS), "settled");
        assertTrue(reg.awaitIdle(5, SECONDS), "idle");
    }

    /** Wait for a timer to end the session. */
    public void awaitGone(String id, long withinMs) throws Exception {
        long until = System.currentTimeMillis() + withinMs;
        while (reg.hasAny(id) && System.currentTimeMillis() < until) Thread.sleep(10);
        quiet(id);
    }

    /** The state of BOTH machines, "supervisor/signaling"; a machine that is not live shows as "-". */
    public String states(String id) {
        String s = reg.supervisorStateOf(id);
        String c = reg.childStateOf(id, signalingType);
        return (s == null ? "-" : s) + "/" + (c == null ? "-" : c);
    }

    /** What the machine sent on the wire for one session, in order. */
    public List<String> wire(String id) {
        return channel.sends.stream().filter(s -> s.requestId().equals(id)).map(s -> String.valueOf(s.command())).toList();
    }

    /** The session ended and both machines are back in their pools. */
    public void assertBackInThePools(String id) {
        assertNull(reg.supervisorStateOf(id), "the supervisor ended");
        assertNull(reg.childStateOf(id, signalingType), "the signaling machine ended");
        assertTrue(reg.wasRecentlyFinished(id));
        assertEquals(0, reg.activeCellCount(), "nothing live");
        assertEquals(supervisorsAtStart, reg.poolStatistics(SUPERVISOR).available(), "the supervisor is back in its pool");
        assertEquals(signalingAtStart, reg.poolStatistics(signalingType).available(), "the signaling machine is back in its pool");
    }

    @Override public void close() { reg.shutdown(); }
}
