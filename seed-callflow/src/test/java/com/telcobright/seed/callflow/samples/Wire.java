package com.telcobright.seed.callflow.samples;

import com.telcobright.seed.callflow.api.CallFlowContext;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.session.HistoryCarrier;
import com.telcobright.statewalk.session.RecordingMachine;
import com.telcobright.statewalk.session.SessionHistory;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SignalingDeferred;
import com.telcobright.statewalk.session.events.SignalingDone;
import com.telcobright.statewalk.session.events.SignalingFailed;
import com.telcobright.statewalk.session.events.SignalingProgress;
import com.telcobright.statewalk.state.StateMap;

import java.util.concurrent.TimeUnit;

/**
 * The signaling child of the sample applications: one leg on a pretend wire. The test plays the far end by delivering
 * {@link Ring}, {@link Answer}, {@link Fail} and {@link Hangup}; the leg reports to its call in the call flow's own words.
 * A real application has its own children (the ESL leg of a call, the SMSC submit of an SMS, the view of an ad).
 */
public final class Wire extends RecordingMachine<Wire.Leg> {

    public static final String TYPE = "Wire";

    public record Ring(String phase) implements StatemachineEvent {}
    public record Answer() implements StatemachineEvent {}
    public record Fail(String cause) implements StatemachineEvent {}
    public record Hangup(String cause, double billedSeconds) implements StatemachineEvent {}
    /** The call ends before any service, by design (the far end asked to try later). */
    public record Defer(String cause) implements StatemachineEvent {}

    /** The leg's context: the call it belongs to. It writes what the wire told onto the call's own context. */
    public static final class Leg implements HistoryCarrier {
        final CallFlowContext call;
        public Leg(CallFlowContext call) { this.call = call; }
        @Override public SessionHistory history() { return call.history; }
        @Override public String historyName() { return "wire"; }
    }

    @Override
    protected StateMap defineStates() {
        return StateMap.builder()
            .initialState("WAITING")
            .state("WAITING")
                .interim()
                .timeout(600, TimeUnit.SECONDS, "GONE")
                .stay(Ring.class, (self, e) -> leg(self).publishEvent(new SignalingProgress(((Ring) e).phase())))
                .on(Answer.class, "ANSWERED", null, (self, e) -> leg(self).publishEvent(new SignalingDone("answered")))
                .on(Fail.class, "GONE", null, (self, e) -> leg(self).publishEvent(new SignalingFailed(((Fail) e).cause())))
                .on(Hangup.class, "GONE", null, (self, e) -> leg(self).publishEvent(new ServiceEnd(((Hangup) e).cause())))
                .on(Defer.class, "GONE", null, (self, e) -> leg(self).publishEvent(new SignalingDeferred(((Defer) e).cause())))
            .state("ANSWERED")
                .interim()
                .timeout(7200, TimeUnit.SECONDS, "GONE")
                .on(Hangup.class, "GONE", null, (self, e) -> leg(self).hungUp((Hangup) e))
            .state("GONE")
                .finalState()
                .timeout(1, TimeUnit.SECONDS, "GONE")
            .build();
    }

    private static Wire leg(Object self) { return (Wire) self; }

    /** The far end hung up an answered call: the billed duration goes onto the call, then the call is told to end. */
    private void hungUp(Hangup hangup) {
        getContext().call.durationSec = hangup.billedSeconds();
        publishEvent(new ServiceEnd(hangup.cause()));
    }
}
