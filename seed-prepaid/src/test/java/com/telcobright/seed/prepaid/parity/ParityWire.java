package com.telcobright.seed.prepaid.parity;

import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.machine.Machine;
import com.telcobright.statewalk.registry.MachineSpec;
import com.telcobright.statewalk.session.Route;
import com.telcobright.statewalk.session.events.Connect;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.Reject;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SessionStart;
import com.telcobright.statewalk.session.events.SignalingFailed;
import com.telcobright.statewalk.session.events.SignalingProgress;
import com.telcobright.statewalk.session.events.Stop;
import com.telcobright.statewalk.state.StateMap;

import java.util.function.BiFunction;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * The parity call's wire for the rows that need the machine (the renewal timer, the cut): the smallest signaling machine statewalk's
 * builder accepts — IDLE ─Dial─► INIT ─Connect↓─► TRYING ─Answer─► ANSWERED ─Hangup | Stop↓─► DONE — the shape of the base's lab call.
 */
public final class ParityWire {

    public static final String TYPE = "ParityCall";

    public record Hop(String carrier) implements Route {}

    public record Dial(CallFacts facts) implements StatemachineEvent { @Override public boolean isFirst() { return true; } }
    public record Answer(long atMs) implements StatemachineEvent {}
    public record Fail(String cause, int code) implements StatemachineEvent {}
    public record Hangup(long atMs) implements StatemachineEvent {}

    public static final class WireContext {
        public String sessionId;
        public String hop;
        public WireContext() {}
    }

    private ParityWire() {}

    public static MachineSpec<WireContext> spec() {
        return MachineSpec.<WireContext>builder()
            .name(TYPE).contextFactory(WireContext::new)
            .upSample(new SessionStart(new CallFacts("sample", 0, Ingress.esl("10.0.0.1", 8021), 701, "8801711000000")))
            .stateMap(StateMap.builder()
                .initialState(StateMap.IDLE)
                .state(StateMap.IDLE).timeout(0)
                    .on(Dial.class, "INIT", null, (self, e) -> ctx(self).sessionId = ((Dial) e).facts().sessionId(), (self, e) -> new SessionStart(((Dial) e).facts()))
                .state("INIT").interim().timeout(0)
                    .on(Connect.class, "TRYING", null, (self, e) -> ctx(self).hop = ((Hop) ((Connect) e).route()).carrier(), (self, e) -> new SignalingProgress(now(), "TRYING", ctx(self).hop))
                    .on(Reject.class, "DONE", null, null, FAILED_BY_THE_COMMAND)
                    .on(Stop.class, "DONE", null, null, FAILED_BY_THE_COMMAND)
                .state("TRYING").interim().timeout(0)
                    .on(Answer.class, "ANSWERED", null, null, (self, e) -> new Established(((Answer) e).atMs(), ctx(self).hop, "PCMA", null))
                    .on(Fail.class, "INIT", null, null, (self, e) -> new SignalingFailed(now(), ((Fail) e).cause(), ((Fail) e).code()))
                    .on(Reject.class, "DONE", null, null, FAILED_BY_THE_COMMAND)
                    .on(Stop.class, "DONE", null, null, FAILED_BY_THE_COMMAND)
                .state("ANSWERED").interim().timeout(0)
                    .on(Hangup.class, "DONE", null, null, (self, e) -> new ServiceEnd(((Hangup) e).atMs(), "NORMAL_CLEARING", "CALLER"))
                    .on(Stop.class, "DONE", null, null, (self, e) -> new ServiceEnd(now(), ((Stop) e).cause(), "SUPERVISOR"))
                    .on(Reject.class, "DONE", null, null, (self, e) -> new ServiceEnd(now(), ((Reject) e).cause(), "SUPERVISOR"))
                .state("DONE").finalState().timeout(1, SECONDS, "DONE")
                .build())
            .build();
    }

    private static final BiFunction<Object, StatemachineEvent, StatemachineEvent> FAILED_BY_THE_COMMAND =
        (self, e) -> new SignalingFailed(now(), e instanceof Reject r ? r.cause() : ((Stop) e).cause(), 403);

    @SuppressWarnings("unchecked")
    static WireContext ctx(Object self) { return ((Machine<WireContext>) self).getContext(); }

    static long now() { return System.currentTimeMillis(); }
}
