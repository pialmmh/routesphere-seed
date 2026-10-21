package com.telcobright.seed.sessionflow.samples.wifi;

import com.telcobright.seed.sessionflow.samples.wifi.WifiEvents.*;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.event.TimeoutEvent;
import com.telcobright.statewalk.machine.Machine;
import com.telcobright.statewalk.registry.MachineSpec;
import com.telcobright.statewalk.session.SessionFacts;
import com.telcobright.statewalk.session.events.Connect;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.Reject;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SessionStart;
import com.telcobright.statewalk.session.events.SignalingFailed;
import com.telcobright.statewalk.session.events.SignalingProgress;
import com.telcobright.statewalk.session.events.Stop;
import com.telcobright.statewalk.state.StateMap;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * The WiFi session's signaling machine (design §7: "WifiGateway — from CaptiveFlow + UsageTracker"): one device's session on a gateway.
 * The gateway's events (login, gate opened, usage, left, gate closed) are its wire in; its commands (open, close, deny) its wire out.
 *
 * <pre>
 *   IDLE ─login─► INIT ─Connect↓─► AUTHORIZING ─gate opened─► ONLINE ─left | Stop↓ | Reject↓ | the maximum─► CLOSING ─gate closed | 5 s─► TERMINATED
 *                  │ Reject↓/Stop↓/5 s  │ refused · left · Reject↓ · Stop↓ · no answer 5 s ─────────────────────────────────────► TERMINATED
 *                                                                  └ usage (stay; the ACTIVE supervisor ignores the progress — the budget child meters)
 * </pre>
 *
 * <p>No re-route (design §7 (f): none): a refused gate ENDS the machine. The money is not here: the base's budget child renews the
 * subscriber's window per tier while the session is ACTIVE, and its exhaustion comes down as {@code Stop(BALANCE_EXHAUSTED)}.
 */
public final class WifiGateway {

    public static final String TYPE = "WifiGateway";

    public static final String IDLE = StateMap.IDLE;
    public static final String INIT = "INIT";
    public static final String AUTHORIZING = "AUTHORIZING";
    public static final String ONLINE = "ONLINE";
    public static final String CLOSING = "CLOSING";
    public static final String TERMINATED = "TERMINATED";

    public static final String NO_DECISION = "NO_DECISION", GATEWAY_TIMEOUT = "GATEWAY_TIMEOUT", DEVICE_LEFT = "DEVICE_LEFT",
        SESSION_TIMEOUT = "SESSION_TIMEOUT";

    /** The machine's timers in ms: INIT, the gateway's answer to open, the session's maximum online, the gateway's answer to close. */
    public record WifiTimings(long initMs, long authorizeMs, long sessionMaxMs, long closingMs) {
        public static WifiTimings production() { return new WifiTimings(5_000, 5_000, 24 * 3600_000L, 5_000); }
    }

    private WifiGateway() {}

    public static MachineSpec<WifiGatewayContext> spec(WifiTimings t, Supplier<WifiGatewayContext> contexts) {
        return spec(t, contexts, WifiFacts::of);
    }

    /** @param factsOf the session's facts from the login — the lane's own record, or the base's facts type once it is known. */
    public static MachineSpec<WifiGatewayContext> spec(WifiTimings t, Supplier<WifiGatewayContext> contexts, Function<DeviceLogin, SessionFacts> factsOf) {
        return MachineSpec.<WifiGatewayContext>builder()
            .name(TYPE).contextFactory(contexts)
            .stateMap(StateMap.builder()
                .initialState(IDLE)

                .state(IDLE).timeout(0)
                    .onEntry(NOTE)
                    .on(DeviceLogin.class, INIT, null, (self, e) -> { DeviceLogin l = (DeviceLogin) e; ctx(self).sessionId = l.sessionId(); ctx(self).mac = l.mac(); },
                        (self, e) -> new SessionStart(factsOf.apply((DeviceLogin) e)))

                .state(INIT).interim().timeout(t.initMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .on(Connect.class, AUTHORIZING, null, OPEN_THE_GATE, (self, e) -> new SignalingProgress(now(), AUTHORIZING, ctx(self).gate.gateId()))
                    .on(Reject.class, TERMINATED, null, DENY_WITH_THE_COMMANDS_CAUSE, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .on(Stop.class, TERMINATED, null, DENY_WITH_THE_COMMANDS_CAUSE, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .stay(TimeoutEvent.class, (self, e) -> send(self, WifiOut.deny(ctx(self).mac, NO_DECISION)), failedWith(NO_DECISION))

                .state(AUTHORIZING).interim().timeout(t.authorizeMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .on(GateOpened.class, ONLINE, null, null,
                        (self, e) -> new Established(((GateOpened) e).atMs(), ctx(self).mac, ((GateOpened) e).policy(), null))   // ↑ CONNECTING → ACTIVE
                    .on(GateRefused.class, TERMINATED, null, (self, e) -> send(self, WifiOut.deny(ctx(self).mac, ((GateRefused) e).cause())),
                        (self, e) -> new SignalingFailed(now(), ((GateRefused) e).cause(), 0))
                    .on(DeviceLeft.class, TERMINATED, null, (self, e) -> send(self, WifiOut.close(ctx(self).mac, DEVICE_LEFT)), failedWith(DEVICE_LEFT))
                    .on(Reject.class, TERMINATED, null, CLOSE_AND_DENY, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .on(Stop.class, TERMINATED, null, CLOSE_AND_DENY, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .stay(TimeoutEvent.class, (self, e) -> send(self, WifiOut.deny(ctx(self).mac, GATEWAY_TIMEOUT)), failedWith(GATEWAY_TIMEOUT))

                .state(ONLINE).interim().timeout(t.sessionMaxMs(), MILLISECONDS, CLOSING)
                    .onEntry(NOTE)
                    .stay(UsageReport.class, KEEP_THE_USAGE, (self, e) -> new SignalingProgress(now(), "USAGE", String.valueOf(ctx(self).bytes)))
                    .on(DeviceLeft.class, CLOSING, null,
                        (self, e) -> { send(self, WifiOut.close(ctx(self).mac, ((DeviceLeft) e).cause()));
                                       ctx(self).ended = new ServiceEnd(((DeviceLeft) e).atMs(), ((DeviceLeft) e).cause(), "SUBSCRIBER"); },
                        (self, e) -> new SignalingProgress(now(), CLOSING, "SUBSCRIBER"))
                    .on(Stop.class, CLOSING, null, CLOSE_FOR_THE_SUPERVISOR, (self, e) -> new SignalingProgress(now(), CLOSING, "SUPERVISOR"))
                    .on(Reject.class, CLOSING, null, CLOSE_FOR_THE_SUPERVISOR, (self, e) -> new SignalingProgress(now(), CLOSING, "SUPERVISOR"))
                    .stay(TimeoutEvent.class,
                        (self, e) -> { send(self, WifiOut.close(ctx(self).mac, SESSION_TIMEOUT)); ctx(self).ended = new ServiceEnd(now(), SESSION_TIMEOUT, "TIMER"); },
                        (self, e) -> new SignalingProgress(now(), CLOSING, "TIMER"))

                .state(CLOSING).interim().timeout(t.closingMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .on(GateClosed.class, TERMINATED, null, KEEP_THE_FINAL_USAGE, (self, e) -> ctx(self).ended)        // ↑ ACTIVE → SUCCEEDED
                    .stay(DeviceLeft.class, null, (self, e) -> new SignalingProgress(now(), CLOSING, "SUBSCRIBER"))
                    .stay(UsageReport.class, KEEP_THE_USAGE, (self, e) -> new SignalingProgress(now(), "USAGE", String.valueOf(ctx(self).bytes)))
                    .stay(Stop.class, null, (self, e) -> new SignalingProgress(now(), CLOSING, "SUPERVISOR"))          // the close is out
                    .stay(Reject.class, null, (self, e) -> new SignalingProgress(now(), CLOSING, "SUPERVISOR"))
                    .stay(TimeoutEvent.class, null, (self, e) -> ctx(self).ended)                                       // no answer: the same end

                .state(TERMINATED).finalState().timeout(1, SECONDS, TERMINATED)
                    .onEntry(NOTE)
                .build())
            .build();
    }

    // ── the actions the table shares ────────────────────────────────

    private static final Consumer<Object> NOTE = self -> ctx(self).road.add(((Machine<?>) self).getCurrentState());

    private static final BiConsumer<Object, StatemachineEvent> OPEN_THE_GATE = (self, e) -> {
        WifiGatewayContext c = ctx(self);
        c.gate = (WifiGate) ((Connect) e).route();
        send(self, WifiOut.open(c.mac, c.gate.policy(), c.gate.gateway()));
    };

    private static final BiConsumer<Object, StatemachineEvent> KEEP_THE_USAGE = (self, e) -> {
        UsageReport u = (UsageReport) e;
        ctx(self).bytes = u.bytes();
        ctx(self).seconds = u.seconds();
        ctx(self).usageReports++;
    };

    private static final BiConsumer<Object, StatemachineEvent> KEEP_THE_FINAL_USAGE = (self, e) -> {
        GateClosed g = (GateClosed) e;
        ctx(self).bytes = g.bytes();
        ctx(self).seconds = g.seconds();
    };

    private static final BiConsumer<Object, StatemachineEvent> DENY_WITH_THE_COMMANDS_CAUSE = (self, e) -> send(self, WifiOut.deny(ctx(self).mac, commandCause(e)));

    /** Reject / Stop while the gate is being opened: close it again (the open may land) and tell the device why. */
    private static final BiConsumer<Object, StatemachineEvent> CLOSE_AND_DENY = (self, e) -> {
        send(self, WifiOut.close(ctx(self).mac, commandCause(e)));
        send(self, WifiOut.deny(ctx(self).mac, commandCause(e)));
    };

    /** The supervisor's Stop (the subscriber's window ran out) or a Reject crossing the answer: the gate is closed, the end built. */
    private static final BiConsumer<Object, StatemachineEvent> CLOSE_FOR_THE_SUPERVISOR = (self, e) -> {
        send(self, WifiOut.close(ctx(self).mac, commandCause(e)));
        ctx(self).ended = new ServiceEnd(now(), commandCause(e), "SUPERVISOR");
    };

    private static final BiFunction<Object, StatemachineEvent, StatemachineEvent> FAILED_WITH_THE_COMMANDS_CAUSE =
        (self, e) -> new SignalingFailed(now(), commandCause(e), 0);

    private static BiFunction<Object, StatemachineEvent, StatemachineEvent> failedWith(String cause) {
        return (self, e) -> { ctx(self).failed = new SignalingFailed(now(), cause, 0); return ctx(self).failed; };
    }

    private static String commandCause(StatemachineEvent e) { return e instanceof Reject r ? r.cause() : ((Stop) e).cause(); }

    @SuppressWarnings("unchecked")
    static WifiGatewayContext ctx(Object self) { return ((Machine<WifiGatewayContext>) self).getContext(); }

    static void send(Object self, WifiOut out) { ((Machine<?>) self).send(out); }

    static long now() { return System.currentTimeMillis(); }
}
