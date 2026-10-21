package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.seed.sessionflow.samples.sms.SmsEvents.*;
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
 * The SMS's signaling machine (design §7: "SmppSubmit"): one message, submitted to an SMSC, then waiting for its delivery receipt.
 *
 * <pre>
 *   IDLE ─request─► INIT ─Connect↓─► SUBMITTING ─submit_sm_resp ok─► SUBMITTED ─deliver_sm (DLR) | Stop↓ | Reject↓ | DLR wait─► TERMINATED
 *                    │ Reject↓/Stop↓/5 s   │ submit_sm_resp error ─► INIT (the supervisor fails over to the next SMSC, or rejects)
 *                    │                     │ Reject↓ · Stop↓ · no submit_sm_resp ──────────────────────────────────────► TERMINATED
 * </pre>
 *
 * <p>The SMS's answer is the SMSC's acceptance ({@code submit_sm_resp} OK): ACTIVE is the wait for the receipt, and the receipt is the
 * service's end — {@code ServiceEnd(DELIVERED | UNDELIVERED | EXPIRED | REJECTED, NETWORK)}, or {@code DLR_TIMEOUT} when none comes. So
 * an accepted message is always SETTLED (D-A1) and the flow's {@code delivered} reads the end cause: delivered pays, anything else takes
 * the lane's refund rule by config. A message the SMSCs never accepted is released (every reserve back). A refused submit goes back to
 * INIT, so a failover to the next SMSC is a second {@code submit_sm} on the same machine.
 */
public final class SmsSubmit {

    public static final String TYPE = "SmsSubmit";

    public static final String IDLE = StateMap.IDLE;
    public static final String INIT = "INIT";
    public static final String SUBMITTING = "SUBMITTING";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String TERMINATED = "TERMINATED";

    public static final String NO_DECISION = "NO_DECISION", SUBMIT_TIMEOUT = "SUBMIT_TIMEOUT", DLR_TIMEOUT = "DLR_TIMEOUT",
        DELIVERED = "DELIVERED";

    /** The machine's timers in ms: INIT, the SMSC's answer to a submit, the wait for the delivery receipt. */
    public record SmsTimings(long initMs, long submitRespMs, long dlrWaitMs) {
        public static SmsTimings production() { return new SmsTimings(5_000, 10_000, 48 * 3600_000L); }
    }

    private SmsSubmit() {}

    public static MachineSpec<SmsSubmitContext> spec(SmsTimings t, Supplier<SmsSubmitContext> contexts) {
        return spec(t, contexts, SmsFacts::of);
    }

    /** @param factsOf the session's facts from the request — the lane's own record, or the base's facts type once it is known. */
    public static MachineSpec<SmsSubmitContext> spec(SmsTimings t, Supplier<SmsSubmitContext> contexts, Function<SmsRequest, SessionFacts> factsOf) {
        return MachineSpec.<SmsSubmitContext>builder()
            .name(TYPE).contextFactory(contexts)
            .stateMap(StateMap.builder()
                .initialState(IDLE)

                .state(IDLE).timeout(0)
                    .onEntry(NOTE)
                    .on(SmsRequest.class, INIT, null, KEEP_THE_MESSAGE, (self, e) -> new SessionStart(factsOf.apply((SmsRequest) e)))

                .state(INIT).interim().timeout(t.initMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .on(Connect.class, SUBMITTING, null, SUBMIT, (self, e) -> new SignalingProgress(now(), SUBMITTING, ctx(self).smsc.smscId()))
                    .on(Reject.class, TERMINATED, null, REFUSE_THE_CLIENT, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .on(Stop.class, TERMINATED, null, REFUSE_THE_CLIENT, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .stay(TimeoutEvent.class, (self, e) -> send(self, SmsOut.toClient("REJECTED " + NO_DECISION)), failedWith(NO_DECISION, 0))

                .state(SUBMITTING).interim().timeout(t.submitRespMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .on(SubmitResp.class, SUBMITTED, (self, e) -> ((SubmitResp) e).status() == 0, ACCEPTED,
                        (self, e) -> new Established(((SubmitResp) e).atMs(), ctx(self).smsc.smscId() + "/" + ctx(self).messageId, null, null))
                    .on(SubmitResp.class, INIT, null, KEEP_THE_REFUSAL, (self, e) -> ctx(self).failed)       // the failover is the supervisor's
                    .on(Reject.class, TERMINATED, null, REFUSE_THE_CLIENT, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .on(Stop.class, TERMINATED, null, REFUSE_THE_CLIENT, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .stay(TimeoutEvent.class, (self, e) -> send(self, SmsOut.toClient("FAILED " + SUBMIT_TIMEOUT)), failedWith(SUBMIT_TIMEOUT, 0))

                .state(SUBMITTED).interim().timeout(t.dlrWaitMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .on(DeliveryReport.class, TERMINATED, null, ANSWER_THE_RECEIPT,
                        (self, e) -> new ServiceEnd(((DeliveryReport) e).atMs(), SmsEvents.deliveryCause(((DeliveryReport) e).stat()), "NETWORK"))
                    .on(Stop.class, TERMINATED, null, (self, e) -> send(self, SmsOut.cancel(ctx(self).messageId)),
                        (self, e) -> new ServiceEnd(now(), ((Stop) e).cause(), "SUPERVISOR"))
                    .on(Reject.class, TERMINATED, null, (self, e) -> send(self, SmsOut.cancel(ctx(self).messageId)),
                        (self, e) -> new ServiceEnd(now(), ((Reject) e).cause(), "SUPERVISOR"))
                    .stay(TimeoutEvent.class, (self, e) -> send(self, SmsOut.toClient("DLR " + DLR_TIMEOUT)),
                        (self, e) -> new ServiceEnd(now(), DLR_TIMEOUT, "TIMER"))

                .state(TERMINATED).finalState().timeout(1, SECONDS, TERMINATED)
                    .onEntry(NOTE)
                .build())
            .build();
    }

    // ── the actions the table shares ────────────────────────────────

    private static final Consumer<Object> NOTE = self -> ctx(self).road.add(((Machine<?>) self).getCurrentState());

    private static final BiConsumer<Object, StatemachineEvent> KEEP_THE_MESSAGE = (self, e) -> {
        SmsRequest r = (SmsRequest) e;
        SmsSubmitContext c = ctx(self);
        c.sessionId = r.sessionId();
        c.sender = r.sender();
        c.receiver = r.receiver();
        c.segments = SmsFacts.segmentsOf(r.text());
    };

    private static final BiConsumer<Object, StatemachineEvent> SUBMIT = (self, e) -> {
        SmsSubmitContext c = ctx(self);
        c.smsc = (Smsc) ((Connect) e).route();
        c.submits++;
        send(self, SmsOut.submit(c.smsc, c.sender, c.receiver, c.segments));
    };

    private static final BiConsumer<Object, StatemachineEvent> ACCEPTED = (self, e) -> {
        ctx(self).messageId = ((SubmitResp) e).messageId();
        send(self, SmsOut.toClient("ACCEPTED " + ctx(self).messageId));
    };

    private static final BiConsumer<Object, StatemachineEvent> KEEP_THE_REFUSAL = (self, e) -> {
        SubmitResp r = (SubmitResp) e;
        ctx(self).failed = new SignalingFailed(r.atMs(), SmsEvents.submitCause(r.status()), r.status());
    };

    private static final BiConsumer<Object, StatemachineEvent> ANSWER_THE_RECEIPT = (self, e) -> {
        DeliveryReport d = (DeliveryReport) e;
        send(self, SmsOut.deliverResp());
        send(self, SmsOut.toClient("DLR " + d.stat()));
    };

    private static final BiConsumer<Object, StatemachineEvent> REFUSE_THE_CLIENT = (self, e) -> send(self, SmsOut.toClient("REJECTED " + commandCause(e)));

    private static final BiFunction<Object, StatemachineEvent, StatemachineEvent> FAILED_WITH_THE_COMMANDS_CAUSE =
        (self, e) -> new SignalingFailed(now(), commandCause(e), 0);

    private static BiFunction<Object, StatemachineEvent, StatemachineEvent> failedWith(String cause, int code) {
        return (self, e) -> { ctx(self).failed = new SignalingFailed(now(), cause, code); return ctx(self).failed; };
    }

    private static String commandCause(StatemachineEvent e) { return e instanceof Reject r ? r.cause() : ((Stop) e).cause(); }

    @SuppressWarnings("unchecked")
    static SmsSubmitContext ctx(Object self) { return ((Machine<SmsSubmitContext>) self).getContext(); }

    static void send(Object self, SmsOut out) { ((Machine<?>) self).send(out); }

    static long now() { return System.currentTimeMillis(); }
}
