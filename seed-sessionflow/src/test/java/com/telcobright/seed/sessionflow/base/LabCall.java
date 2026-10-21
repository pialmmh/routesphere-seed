package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.base.LabEvents.Answer;
import com.telcobright.seed.sessionflow.base.LabEvents.Dial;
import com.telcobright.seed.sessionflow.base.LabEvents.Fail;
import com.telcobright.seed.sessionflow.base.LabEvents.Hangup;
import com.telcobright.seed.sessionflow.base.LabEvents.Ring;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.machine.Machine;
import com.telcobright.statewalk.registry.MachineSpec;
import com.telcobright.statewalk.session.events.Connect;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.Reject;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SessionStart;
import com.telcobright.statewalk.session.events.SignalingFailed;
import com.telcobright.statewalk.session.events.SignalingProgress;
import com.telcobright.statewalk.session.events.Stop;
import com.telcobright.statewalk.state.StateMap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * The lab's signaling machine — the smallest one statewalk 4.0's builder accepts: IDLE first, an {@code up} on every transition and
 * stay, Reject and Stop handled in every live state, no own timers (the supervisor's govern; the registry's lifetime cap backs them).
 *
 * <pre>
 *   IDLE ─Dial─► INIT ─Connect↓─► TRYING ─Answer─► ANSWERED ─Hangup | Stop↓ | Reject↓─► DONE
 *                 │ Reject↓/Stop↓   │ Fail ─► INIT (the supervisor re-routes or rejects)
 *                 └─► DONE          └ Reject↓/Stop↓ ─► DONE
 * </pre>
 */
public final class LabCall {

    public static final String TYPE = "LabCall";

    /** The machine's context: the hops it was sent to and the states it saw. */
    public static final class LabCallContext {
        public String sessionId;
        public String hop;
        public List<String> hops = new ArrayList<>();
        public LabCallContext() {}
    }

    private LabCall() {}

    public static MachineSpec<LabCallContext> spec() {
        return MachineSpec.<LabCallContext>builder()
            .name(TYPE).contextFactory(LabCallContext::new)
            .stateMap(StateMap.builder()
                .initialState(StateMap.IDLE)
                .state(StateMap.IDLE).timeout(0)
                    .on(Dial.class, "INIT", null, (self, e) -> ctx(self).sessionId = ((Dial) e).facts().sessionId(), (self, e) -> new SessionStart(((Dial) e).facts()))
                .state("INIT").interim().timeout(0)
                    .on(Connect.class, "TRYING", null, (self, e) -> invite(self, (Connect) e), (self, e) -> new SignalingProgress(now(), "TRYING", ctx(self).hop))
                    .on(Reject.class, "DONE", null, null, FAILED_BY_THE_COMMAND)
                    .on(Stop.class, "DONE", null, null, FAILED_BY_THE_COMMAND)
                .state("TRYING").interim().timeout(0)
                    .stay(Ring.class, null, (self, e) -> new SignalingProgress(now(), "RINGING", null))
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

    private static void invite(Object self, Connect connect) {
        LabCallContext c = ctx(self);
        c.hop = ((LabHop) connect.route()).carrier();
        c.hops.add(c.hop);
        ((Machine<?>) self).send("INVITE " + c.hop);
    }

    @SuppressWarnings("unchecked")
    static LabCallContext ctx(Object self) { return ((Machine<LabCallContext>) self).getContext(); }

    static long now() { return System.currentTimeMillis(); }
}
