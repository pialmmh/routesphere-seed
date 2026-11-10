package com.telcobright.seed.memledgerremote.internal;

import com.telcobright.seed.memledgerremote.wire.Nothing;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ARCH-0118 · What the server says it does ({@code HealthReply.capabilities}): asked at the start once the server answered its first
 * call (a lab's channel: when the client is made), and asked AGAIN once the connection it was asked on may be gone. A server restarted on the same address may be another version (an upgrade, a roll-back): the
 * client follows the words of the server it talks to now, never those of the process it first met. Every change of the channel's state
 * marks the words as an earlier connection's; the next question asks Health again. A server that says none is an older one, said in the
 * log: the client then keeps the client-side ghost rule and sends no rows as read — one writer per instance.
 *
 * <p>What stays open: the channel's change of state reaches this watch a moment after the connection closed (on the channel's
 * executor), and a question asked in that moment gets the earlier connection's words. A server's restart takes seconds; the moment is
 * milliseconds. A channel that does not tell its state is asked once, at the connect, said in the log.
 */
public final class ServerCapabilities {

    private static final Logger log = LoggerFactory.getLogger(ServerCapabilities.class);

    private final Calls calls;
    private final String address;
    private final AtomicLong channelChanges = new AtomicLong();
    private volatile Said said;

    /** The words, and how many times the channel had changed when they were asked. */
    private record Said(long channelChanges, Set<String> words) {}

    private ServerCapabilities(Calls calls, String address) {
        this.calls = calls;
        this.address = address;
    }

    /** Asked now — a server that does not answer refuses the start — and again after every change of {@code channel}'s state. */
    public static ServerCapabilities askedOf(ManagedChannel channel, Calls calls, String address) {
        ServerCapabilities capabilities = followedOn(channel, calls, address);
        capabilities.now();
        return capabilities;
    }

    /**
     * Not asked yet: the first {@link #now()} asks — the start asks once its wait for the server's first answer is over (remote-fixes
     * item (d): a server just started may not serve yet) — and again after every change of {@code channel}'s state.
     */
    public static ServerCapabilities followedOn(ManagedChannel channel, Calls calls, String address) {
        ServerCapabilities capabilities = new ServerCapabilities(calls, address);
        capabilities.followTheChannel(channel);
        return capabilities;
    }

    /** What the server of this connection says it does. */
    public Set<String> now() {
        long changes = channelChanges.get();
        Said known = said;
        if (known != null && known.channelChanges() == changes) return known.words();
        Set<String> words = asked();
        said = new Said(changes, words);
        return words;
    }

    private Set<String> asked() {
        Set<String> words = Set.copyOf(calls.call("Health", s -> s.health(Nothing.getDefaultInstance())).getCapabilitiesList());
        if (words.isEmpty()) {
            log.warn("mem-ledger client: the server at {} says no capabilities — an older server: this client takes out the ghost reserves of a reload itself and sends no rows as read (one writer per instance)", address);
        } else {
            log.info("mem-ledger client: the server at {} says {}", address, words);
        }
        return words;
    }

    private void followTheChannel(ManagedChannel channel) {
        try {
            watch(channel, channel.getState(false));
        } catch (UnsupportedOperationException noStates) {
            log.warn("mem-ledger client: the channel to {} does not tell its state — the server's words are asked once, at the connect", address);
        }
    }

    /** One change of the channel's state: the words may be another server's; watch the next one (until the channel is shut down). */
    private void watch(ManagedChannel channel, ConnectivityState seen) {
        channel.notifyWhenStateChanged(seen, () -> {
            channelChanges.incrementAndGet();
            ConnectivityState now = channel.getState(false);
            if (now != ConnectivityState.SHUTDOWN) watch(channel, now);
        });
    }
}
