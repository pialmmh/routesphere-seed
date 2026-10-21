package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.seed.sessionflow.samples.ad.AdEvents.*;
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
 * The ad's ONE signaling machine (design routesphere-flow.html §6, [assumes D1]): today's three children of a view (AdView, AdStream,
 * AdCredit) as one {@link MachineSpec} — states and transitions only, IDLE first, every road with its {@code up}. The phone's HTTP roads
 * are its wire: the adapter turns each road into one frame ({@link AdFrame}), the machine answers each road through {@code send}.
 *
 * <pre>
 *   IDLE ─POST ad-sessions─► INIT ─Connect↓─► OFFERED ─/shown─► SHOWING ─/viewed─► VIEWED ─/credited | window─► TERMINATED
 *                              │ Reject↓/Stop↓/5 s   │ /abandoned · Reject↓ · Stop↓ · show timer │ /progress (stay)  │ Stop↓ / Reject↓
 *                              └──────────────────────┴── /abandoned · Reject↓ · Stop↓ · stall timer ───────────────┴──────────► TERMINATED
 * </pre>
 *
 * <p>The ad's answer stays at VIEWED and ACTIVE stays the credit window (today's mapping). The {@code Established} of a view carries
 * the creative as its remote party, the media kind as its codec and the seconds watched in its {@code sdp} field — the record has no
 * field of its own for them ({@link #watchedSecondsOf}).
 */
public final class AdPlayback {

    public static final String TYPE = "AdPlayback";

    public static final String IDLE = StateMap.IDLE;
    public static final String INIT = "INIT";
    public static final String OFFERED = "OFFERED";
    public static final String SHOWING = "SHOWING";
    public static final String VIEWED = "VIEWED";
    public static final String TERMINATED = "TERMINATED";

    /** The cause words this machine writes. */
    public static final String NO_DECISION = "NO_DECISION", NOT_SHOWN = "NOT_SHOWN", STALLED = "STALLED", ABANDONED = "ABANDONED",
        CREDITED = "CREDITED", CREDIT_WINDOW_EXPIRED = "CREDIT_WINDOW_EXPIRED";

    /**
     * The machine's timers, in ms: INIT (the supervisor decides), OFFERED (the phone shows the creative within it, else NOT_SHOWN),
     * SHOWING (the view completes within it, else STALLED), VIEWED (the credit window).
     */
    public record AdTimings(long initMs, long showWithinMs, long stallMs, long creditWindowMs) {
        public static AdTimings production() { return new AdTimings(5_000, 30_000, 120_000, 600_000); }
    }

    private AdPlayback() {}

    public static MachineSpec<AdPlaybackContext> spec(AdTimings t, Supplier<AdPlaybackContext> contexts) {
        return spec(t, contexts, AdFacts::of);
    }

    /** @param factsOf the session's facts from the request — the lane's own record, or the base's facts type once it is known. */
    public static MachineSpec<AdPlaybackContext> spec(AdTimings t, Supplier<AdPlaybackContext> contexts, Function<AdRequest, SessionFacts> factsOf) {
        return MachineSpec.<AdPlaybackContext>builder()
            .name(TYPE).contextFactory(contexts)
            .stateMap(StateMap.builder()
                .initialState(IDLE)

                .state(IDLE).timeout(0)                                                   // the pooled machine rests here
                    .onEntry(NOTE)
                    .on(AdRequest.class, INIT, null, (self, e) -> ctx(self).sessionId = ((AdRequest) e).sessionId(),   // the HTTP call waits
                        (self, e) -> new SessionStart(factsOf.apply((AdRequest) e)))      // ↑ IDLE → INIT → ADMITTING → ROUTING

                .state(INIT).interim().timeout(t.initMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .on(Connect.class, OFFERED, null, HAND_OVER,
                        (self, e) -> new SignalingProgress(now(), OFFERED, ctx(self).creative.creativeId()))
                    .on(Reject.class, TERMINATED, null, NO_AD_FOR_THE_PHONE, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .on(Stop.class, TERMINATED, null, NO_AD_FOR_THE_PHONE, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .stay(TimeoutEvent.class, (self, e) -> send(self, AdOut.noAd(NO_DECISION)), failedWith(NO_DECISION))

                .state(OFFERED).interim().timeout(t.showWithinMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .on(AdShown.class, SHOWING, null,
                        (self, e) -> { ctx(self).shownAtMs = ((AdShown) e).atMs(); send(self, AdOut.ok("shown")); },
                        (self, e) -> new SignalingProgress(now(), SHOWING, String.valueOf(ctx(self).shownAtMs)))
                    .on(AdAbandoned.class, TERMINATED, null, ANSWER_THE_ABANDON, failedWith(ABANDONED))
                    .on(Reject.class, TERMINATED, null, null, FAILED_WITH_THE_COMMANDS_CAUSE)      // the phone already has the view
                    .on(Stop.class, TERMINATED, null, null, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .stay(TimeoutEvent.class, null, failedWith(NOT_SHOWN))                         // the view timer: never shown

                .state(SHOWING).interim().timeout(t.stallMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .stay(AdProgress.class,
                        (self, e) -> { ctx(self).beacons++; ctx(self).watchedSeconds = ((AdProgress) e).watchedSeconds(); send(self, AdOut.ok("progress")); },
                        (self, e) -> new SignalingProgress(now(), "BEACON", String.valueOf(ctx(self).watchedSeconds)))
                    .on(AdViewed.class, VIEWED, null, KEEP_THE_VIEW,
                        (self, e) -> new Established(ctx(self).viewedAtMs, ctx(self).creative.creativeId(), ctx(self).creative.mediaKind(),
                                                     String.valueOf(ctx(self).watchedSeconds)))       // ↑ CONNECTING → ACTIVE: the credit window
                    .on(AdAbandoned.class, TERMINATED, null, ANSWER_THE_ABANDON, failedWith(ABANDONED))
                    .on(Reject.class, TERMINATED, null, null, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .on(Stop.class, TERMINATED, null, null, FAILED_WITH_THE_COMMANDS_CAUSE)
                    .stay(TimeoutEvent.class, null, failedWith(STALLED))                           // the stall timer

                .state(VIEWED).interim().timeout(t.creditWindowMs(), MILLISECONDS, TERMINATED)
                    .onEntry(NOTE)
                    .on(AdCredited.class, TERMINATED, null, (self, e) -> send(self, AdOut.ok("credited")),
                        (self, e) -> new ServiceEnd(((AdCredited) e).atMs(), CREDITED, "SUBSCRIBER"))       // ↑ ACTIVE → SUCCEEDED
                    .on(Stop.class, TERMINATED, null, null, (self, e) -> new ServiceEnd(now(), ((Stop) e).cause(), "SUPERVISOR"))
                    .on(Reject.class, TERMINATED, null, null, (self, e) -> new ServiceEnd(now(), ((Reject) e).cause(), "SUPERVISOR"))
                    .stay(TimeoutEvent.class, null, (self, e) -> new ServiceEnd(now(), CREDIT_WINDOW_EXPIRED, "TIMER"))

                .state(TERMINATED).finalState().timeout(1, SECONDS, TERMINATED)
                    .onEntry(NOTE)                                                        // then the registry resets the machine to IDLE
                .build())
            .build();
    }

    /** The seconds a view's answer says were watched (its {@code sdp} field, see the class note). */
    public static int watchedSecondsOf(Established answer) {
        return answer == null || answer.sdp() == null ? 0 : Integer.parseInt(answer.sdp());
    }

    // ── the actions the table shares ────────────────────────────────

    private static final Consumer<Object> NOTE = self -> ctx(self).road.add(((Machine<?>) self).getCurrentState());

    /** Connect(creative): the hand-over — {@code 200 AdSessionView} answers the waiting {@code POST ad-sessions}. */
    private static final BiConsumer<Object, StatemachineEvent> HAND_OVER = (self, e) -> {
        AdPlaybackContext c = ctx(self);
        c.creative = (AdCreative) ((Connect) e).route();
        c.offeredAtMs = now();
        send(self, AdOut.view(c.creative));
    };

    /** Reject/Stop before the hand-over: {@code 204} with the cause in {@code X-Ad-Reason}. */
    private static final BiConsumer<Object, StatemachineEvent> NO_AD_FOR_THE_PHONE = (self, e) -> send(self, AdOut.noAd(commandCause(e)));

    private static final BiConsumer<Object, StatemachineEvent> ANSWER_THE_ABANDON = (self, e) -> send(self, AdOut.ok("abandoned"));

    private static final BiConsumer<Object, StatemachineEvent> KEEP_THE_VIEW = (self, e) -> {
        AdViewed v = (AdViewed) e;
        ctx(self).viewedAtMs = v.atMs();
        ctx(self).watchedSeconds = v.watchedSeconds();
        send(self, AdOut.verdict(v.watchedSeconds()));
    };

    private static final BiFunction<Object, StatemachineEvent, StatemachineEvent> FAILED_WITH_THE_COMMANDS_CAUSE =
        (self, e) -> new SignalingFailed(now(), commandCause(e), 0);

    private static BiFunction<Object, StatemachineEvent, StatemachineEvent> failedWith(String cause) {
        return (self, e) -> { ctx(self).failed = new SignalingFailed(now(), cause, 0); return ctx(self).failed; };
    }

    private static String commandCause(StatemachineEvent e) { return e instanceof Reject r ? r.cause() : ((Stop) e).cause(); }

    @SuppressWarnings("unchecked")
    static AdPlaybackContext ctx(Object self) { return ((Machine<AdPlaybackContext>) self).getContext(); }

    static void send(Object self, AdOut out) { ((Machine<?>) self).send(out); }

    static long now() { return System.currentTimeMillis(); }
}
