package com.telcobright.seed.sessionflow.internal;

import com.telcobright.statewalk.channel.Channel;

/**
 * The lane's channel behind the engine's door counter (P1-F): every frame the channel offers reaches the registry as before, and a NEW
 * session the registry refuses because every machine is live is counted {@code busy} ({@link BusyAtTheDoor}). Everything else is the
 * lane's channel's own: the sends, the cancels, the stop, the state, the name. The registry holds this channel; {@link #lane()} is the
 * lane's own object.
 */
public final class DoorChannel<O, I> implements Channel<O, I> {

    private final Channel<O, I> lane;
    private final FlowCounters counters;

    public DoorChannel(Channel<O, I> lane, FlowCounters counters) {
        this.lane = lane;
        this.counters = counters;
    }

    /** The lane's own channel. */
    public Channel<O, I> lane() { return lane; }

    @Override public void send(String requestId, O command) { lane.send(requestId, command); }

    @Override public void cancel(String requestId) { lane.cancel(requestId); }

    /** The lane's channel starts with a gateway that counts the registry's busy refusals, then hands every frame on unchanged. */
    @Override public void start(Inbound<I> gateway) { lane.start((requestId, event) -> BusyAtTheDoor.counted(gateway.offer(requestId, event), counters)); }

    @Override public void stop() { lane.stop(); }

    @Override public boolean isConnected() { return lane.isConnected(); }

    @Override public String getName() { return lane.getName(); }

    @Override public String toString() { return lane.toString(); }
}
