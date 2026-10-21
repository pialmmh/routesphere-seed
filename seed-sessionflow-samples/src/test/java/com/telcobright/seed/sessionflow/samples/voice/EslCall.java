package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.seed.sessionflow.samples.voice.EslEvents.*;
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
 * The ESL call machine, one per call (design §7: "EslCall — today's EslCallSignaling, re-shaped: IDLE first, every transition with
 * up"): the parked A-leg is transferred into the dialplan on {@code Connect}; FreeSWITCH's events come back as frames.
 *
 * <pre>
 *   IDLE ─CHANNEL_PARK─► INIT ─Connect↓─► TRYING ─PROGRESS─► RINGING ─ANSWER─► ANSWERED ─HANGUP | Stop↓ | Reject↓ | max─► DISCONNECTING ─HANGUP_COMPLETE | 5 s─► TERMINATED
 *                         │ Reject↓/Stop↓/5 s  │ bridge failed ─► INIT (the supervisor re-routes: a second uuid_transfer, or rejects: uuid_kill)
 *                         │ caller hangup      │ caller hangup · Reject↓ · Stop↓ (uuid_kill) · no progress 8 s · no answer 60 s ─► TERMINATED
 * </pre>
 *
 * <p>A failed attempt goes back to INIT, as the SIP example's carrier error does: the A-leg is still parked, so the supervisor's
 * re-route ({@code Connect} of the next hop — or the same hop again for RETRY_SAME) is a second {@code uuid_transfer} on the same
 * machine (CN-2: a retry needs a live signaling machine). The two pre-answer deadlines (C11) live here: TRYING's no-progress and
 * RINGING's no-answer; each ENDS the machine (no re-route after them).
 */
public final class EslCall {

    public static final String TYPE = "EslCall";

    public static final String IDLE = StateMap.IDLE;
    public static final String INIT = "INIT";
    public static final String TRYING = "TRYING";
    public static final String RINGING = "RINGING";
    public static final String ANSWERED = "ANSWERED";
    public static final String DISCONNECTING = "DISCONNECTING";
    public static final String TERMINATED = "TERMINATED";

    public static final String NO_DECISION = "NO_DECISION", NO_PROGRESS = "NO_PROGRESS", NO_ANSWER = "NO_ANSWER",
        ORIGINATOR_CANCEL = "ORIGINATOR_CANCEL", MAX_DURATION = "MAX_DURATION";

    /** The machine's timers in ms (INIT 5 s, TRYING 8 s, RINGING 60 s, the call's maximum, DISCONNECTING 5 s). */
    public record EslTimings(long initMs, long tryingMs, long ringingMs, long maxCallMs, long disconnectingMs) {
        public static EslTimings production() { return new EslTimings(5_000, 8_000, 60_000, 4 * 3600_000L, 5_000); }
    }

    private EslCall() {}

    public static MachineSpec<EslCallContext> spec(EslTimings t, Supplier<EslCallContext> contexts) {
        return spec(t, contexts, EslFacts::of);
    }

    /** @param factsOf the session's facts from the park — the lane's own record, or the base's facts type once it is known. */
    public static MachineSpec<EslCallContext> spec(EslTimings t, Supplier<EslCallContext> contexts, Function<ChannelPark, SessionFacts> factsOf) {
        return MachineSpec.<EslCallContext>builder()
            .name(TYPE).contextFactory(contexts)
            .stateMap(StateMap.builder()
                .initialState(IDLE)

                .state(IDLE).timeout(0)
                    .onEntry(NOTE)
                    .on(ChannelPark.class, INIT, null, (self, e) -> { ChannelPark p = (ChannelPark) e; ctx(self).uuid = p.uuid(); ctx(self).caller = p.caller(); },
                        (self, e) -> new SessionStart(factsOf.apply((ChannelPark) e)))          // ↑ the leg waits, parked

                .state(INIT).interim().timeout(t.initMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .on(Connect.class, TRYING, null, TRANSFER_INTO_THE_DIALPLAN,
                        (self, e) -> new SignalingProgress(now(), TRYING, ctx(self).target.routeName()))
                    .on(Reject.class, TERMINATED, null, KILL_WITH_THE_COMMANDS_CAUSE, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .on(Stop.class, TERMINATED, null, KILL_WITH_THE_COMMANDS_CAUSE, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .on(ChannelHangup.class, TERMINATED, null, null, failedWith(ORIGINATOR_CANCEL))   // the caller gave up while parked
                    .stay(TimeoutEvent.class, (self, e) -> kill(self, "NORMAL_TEMPORARY_FAILURE"), failedWith(NO_DECISION))

                .state(TRYING).interim().timeout(t.tryingMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .on(ChannelProgress.class, RINGING, null, null, PROGRESS)
                    .on(ChannelAnswer.class, ANSWERED, null, null, ESTABLISHED)
                    .on(BridgeFailed.class, INIT, null, KEEP_THE_ATTEMPTS_FAILURE, (self, e) -> ctx(self).failed)
                    .stay(ChannelBridge.class, KEEP_THE_OTHER_LEG, BRIDGED)
                    .on(ChannelHangup.class, TERMINATED, null, KILL_THE_OTHER_LEG, failedWith(ORIGINATOR_CANCEL))
                    .on(Reject.class, TERMINATED, null, KILL_WITH_THE_COMMANDS_CAUSE, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .on(Stop.class, TERMINATED, null, KILL_WITH_THE_COMMANDS_CAUSE, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .stay(TimeoutEvent.class, (self, e) -> kill(self, "RECOVERY_ON_TIMER_EXPIRE"), failedWith(NO_PROGRESS))

                .state(RINGING).interim().timeout(t.ringingMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .stay(ChannelProgress.class, null, PROGRESS)                              // later progress: the window is not re-armed
                    .on(ChannelAnswer.class, ANSWERED, null, null, ESTABLISHED)
                    .on(BridgeFailed.class, INIT, null, KEEP_THE_ATTEMPTS_FAILURE, (self, e) -> ctx(self).failed)
                    .stay(ChannelBridge.class, KEEP_THE_OTHER_LEG, BRIDGED)
                    .on(ChannelHangup.class, TERMINATED, null, KILL_THE_OTHER_LEG, failedWith(ORIGINATOR_CANCEL))
                    .on(Reject.class, TERMINATED, null, KILL_WITH_THE_COMMANDS_CAUSE, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .on(Stop.class, TERMINATED, null, KILL_WITH_THE_COMMANDS_CAUSE, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .stay(TimeoutEvent.class, (self, e) -> kill(self, "NO_ANSWER"), failedWith(NO_ANSWER))

                .state(ANSWERED).interim().timeout(t.maxCallMs(), MILLISECONDS, DISCONNECTING)
                    .onEntry(NOTE)
                    .on(ChannelHangup.class, DISCONNECTING, null, HANGUP_FROM_A_SIDE,
                        (self, e) -> new SignalingProgress(now(), DISCONNECTING, ctx(self).ended.endedBy()))
                    .on(Stop.class, DISCONNECTING, null, KILL_FOR_THE_SUPERVISOR, (self, e) -> new SignalingProgress(now(), DISCONNECTING, "SUPERVISOR"))
                    .on(Reject.class, DISCONNECTING, null, KILL_FOR_THE_SUPERVISOR, (self, e) -> new SignalingProgress(now(), DISCONNECTING, "SUPERVISOR"))
                    .stay(ChannelBridge.class, KEEP_THE_OTHER_LEG, BRIDGED)
                    .stay(TimeoutEvent.class,
                        (self, e) -> { kill(self, "ALLOTTED_TIMEOUT"); ctx(self).ended = new ServiceEnd(now(), MAX_DURATION, "TIMER"); },
                        (self, e) -> new SignalingProgress(now(), DISCONNECTING, "TIMER"))

                .state(DISCONNECTING).interim().timeout(t.disconnectingMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .on(ChannelHangupComplete.class, TERMINATED, null, null, (self, e) -> ctx(self).ended)   // ↑ ACTIVE → SUCCEEDED
                    .stay(ChannelHangup.class, null, (self, e) -> new SignalingProgress(now(), DISCONNECTING, ((ChannelHangup) e).aLeg() ? "CALLER" : "CALLEE"))
                    .stay(Stop.class, null, (self, e) -> new SignalingProgress(now(), DISCONNECTING, "SUPERVISOR"))      // the kill is out
                    .stay(Reject.class, null, (self, e) -> new SignalingProgress(now(), DISCONNECTING, "SUPERVISOR"))
                    .stay(TimeoutEvent.class, null, (self, e) -> ctx(self).ended)                  // no HANGUP_COMPLETE: the same end

                .state(TERMINATED).finalState().timeout(1, SECONDS, TERMINATED)
                    .onEntry(NOTE)
                .build())
            .build();
    }

    // ── the actions the table shares ────────────────────────────────

    private static final Consumer<Object> NOTE = self -> ctx(self).road.add(((Machine<?>) self).getCurrentState());

    /** C10: the caller id ×4, the target, then the transfer of the parked leg into the dialplan. */
    private static final BiConsumer<Object, StatemachineEvent> TRANSFER_INTO_THE_DIALPLAN = (self, e) -> {
        EslCallContext c = ctx(self);
        c.target = (EslTarget) ((Connect) e).route();
        c.transfers++;
        for (String var : new String[] {"origination_caller_id_number", "origination_caller_id_name", "effective_caller_id_number", "effective_caller_id_name"}) {
            send(self, EslOut.setvar(c.uuid, var, c.caller));
        }
        send(self, EslOut.setvar(c.uuid, "target_ip", c.target.targetIp()));
        send(self, EslOut.setvar(c.uuid, "sofia_profile", c.target.sofiaProfile()));
        send(self, EslOut.transfer(c.uuid, c.target.destination(), c.target.context()));
    };

    private static final BiFunction<Object, StatemachineEvent, StatemachineEvent> PROGRESS = (self, e) -> {
        ChannelProgress p = (ChannelProgress) e;
        return new SignalingProgress(p.atMs(), p.media() ? "EARLY_MEDIA" : "RINGING", p.sdp());
    };

    private static final BiFunction<Object, StatemachineEvent, StatemachineEvent> ESTABLISHED = (self, e) -> {
        ChannelAnswer a = (ChannelAnswer) e;
        return new Established(a.atMs(), a.otherParty(), a.codec(), a.sdp());
    };

    private static final BiConsumer<Object, StatemachineEvent> KEEP_THE_ATTEMPTS_FAILURE = (self, e) -> {
        BridgeFailed f = (BridgeFailed) e;
        ctx(self).failed = new SignalingFailed(f.atMs(), f.cause(), f.q850());
        ctx(self).otherUuid = null;
    };

    private static final BiConsumer<Object, StatemachineEvent> KEEP_THE_OTHER_LEG = (self, e) -> ctx(self).otherUuid = ((ChannelBridge) e).otherUuid();

    private static final BiFunction<Object, StatemachineEvent, StatemachineEvent> BRIDGED =
        (self, e) -> new SignalingProgress(now(), "BRIDGED", ((ChannelBridge) e).otherUuid());

    /** Before the answer: the caller hung up — the attempt's other leg, if any, goes too. */
    private static final BiConsumer<Object, StatemachineEvent> KILL_THE_OTHER_LEG = (self, e) -> {
        if (ctx(self).otherUuid != null) send(self, EslOut.kill(ctx(self).otherUuid, ORIGINATOR_CANCEL));
    };

    /** C15: either side hung up an answered call — the other leg is killed; the end is built once (cause, who). */
    private static final BiConsumer<Object, StatemachineEvent> HANGUP_FROM_A_SIDE = (self, e) -> {
        ChannelHangup h = (ChannelHangup) e;
        EslCallContext c = ctx(self);
        String other = h.aLeg() ? c.otherUuid : c.uuid;
        if (other != null) send(self, EslOut.kill(other, h.cause()));
        c.ended = new ServiceEnd(h.atMs(), h.cause(), h.aLeg() ? "CALLER" : "CALLEE");
    };

    /** The supervisor's Stop (or a Reject crossing the answer) after the answer: both legs killed. */
    private static final BiConsumer<Object, StatemachineEvent> KILL_FOR_THE_SUPERVISOR = (self, e) -> {
        String cause = commandCause(e);
        kill(self, EslEvents.hangupCauseFor(cause));
        ctx(self).ended = new ServiceEnd(now(), cause, "SUPERVISOR");
    };

    /** Reject / Stop before the answer: the parked (or ringing) leg is killed with the cause's hangup word. */
    private static final BiConsumer<Object, StatemachineEvent> KILL_WITH_THE_COMMANDS_CAUSE =
        (self, e) -> kill(self, EslEvents.hangupCauseFor(commandCause(e)));

    private static final BiFunction<Object, StatemachineEvent, StatemachineEvent> FAILED_WITH_THE_COMMANDS_CAUSE = (self, e) -> {
        String cause = commandCause(e);
        return new SignalingFailed(now(), cause, EslEvents.q850(EslEvents.hangupCauseFor(cause)));
    };

    private static BiFunction<Object, StatemachineEvent, StatemachineEvent> failedWith(String cause) {
        return (self, e) -> { ctx(self).failed = new SignalingFailed(now(), cause, EslEvents.q850(cause)); return ctx(self).failed; };
    }

    /** {@code uuid_kill} of the A-leg: FreeSWITCH tears the bridge down with it. */
    private static void kill(Object self, String hangupCause) { send(self, EslOut.kill(ctx(self).uuid, hangupCause)); }

    private static String commandCause(StatemachineEvent e) { return e instanceof Reject r ? r.cause() : ((Stop) e).cause(); }

    @SuppressWarnings("unchecked")
    static EslCallContext ctx(Object self) { return ((Machine<EslCallContext>) self).getContext(); }

    static void send(Object self, EslOut out) { ((Machine<?>) self).send(out); }

    static long now() { return System.currentTimeMillis(); }
}
