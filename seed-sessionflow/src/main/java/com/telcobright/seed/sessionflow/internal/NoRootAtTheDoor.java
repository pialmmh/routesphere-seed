package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * P1-K · A request refused because the lane's rule named no root — the owner's ruling of 2026-10-24: "if we fail to identify the root
 * level tenant at the ingress points, the request shall be rejected and logs shall be generated". Every refusal is COUNTED
 * ({@code refusedNoRoot}) and SAID as a WARN line: the app, the door (the ingress's kind and value: {@code listen 10.10.188.2:7070}), the
 * session id when there is one, and the reason in words. The rejection itself is the ingress's own protocol answer.
 *
 * <p>Flood-safe, per door: the first refusal in each {@value #WINDOW_MS} ms window is said in full; the rest of that window are counted and
 * said as ONE summary line ("… and N more refused at &lt;door&gt; in the 60 s window from &lt;its start&gt;"), written at the door's next
 * refusal after the window — no timer thread. At most {@value #MOST_DOORS} doors are held apart; a refusal at any further door shares one
 * window, "{@value #OTHER_DOORS}" (memory stays bounded). Rule one: with debug on, EVERY refusal is said.
 *
 * <p>P1-L · ONE sayer per kit (beside its counters), for both roads: the ingress's door ({@link #refused}) and the machine's start that
 * refuses {@code NO_TENANT} ({@link #refusedAtTheStart}) — the same window per door, the same count. Every value it writes that a request
 * may carry — the door, the session id, a failed rule's words and its stack — is cleaned by {@link LogText} (control characters escaped,
 * at most 200 characters); a door's window is kept under its cleaned name.
 */
public final class NoRootAtTheDoor {

    public static final long WINDOW_MS = 60_000;
    static final int MOST_DOORS = 1024;
    static final String OTHER_DOORS = "other doors";
    private static final int KEPT_LINES = 64;
    private static final String AT_THE_DOOR = "the request is refused", AT_THE_START = "the session is refused NO_TENANT";
    private static final Logger log = LoggerFactory.getLogger(NoRootAtTheDoor.class);

    private final Clock clock;
    private final FlowCounters counters;
    private final BooleanSupplier debug;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Deque<String> said = new ArrayDeque<>();

    public NoRootAtTheDoor(Clock clock, FlowCounters counters, BooleanSupplier debug) {
        this.clock = clock;
        this.counters = counters;
        this.debug = debug;
    }

    /** The ingress's door ({@code engine.identifyRootLevelTenant}): the request is refused — counted, and said by the door's window. */
    public void refused(String app, RoutesphereFacts facts, RuntimeException ruleFailed) {
        refuse(app, doorOf(facts), sessionOf(facts == null ? null : facts.sessionId()), whyOf(facts, ruleFailed) + " — " + AT_THE_DOOR, ruleFailed);
    }

    /**
     * P1-L · The machine's start: a session whose facts carry no root and whose lane's rule names none is refused {@code NO_TENANT} —
     * counted and said like a refusal at the door, in the same window. Facts that are not {@code RoutesphereFacts} name no door.
     */
    public void refusedAtTheStart(String app, String sessionId, Object facts, RuntimeException ruleFailed) {
        RoutesphereFacts withADoor = facts instanceof RoutesphereFacts f ? f : null;
        String why = withADoor != null || facts == null ? whyOf(withADoor, ruleFailed)
            : "the session's facts are " + LogText.clean(facts.getClass().getName()) + ", not RoutesphereFacts: they name no ingress";
        refuse(app, doorOf(withADoor), sessionOf(sessionId), why + " — " + AT_THE_START, ruleFailed);
    }

    /** The last lines said (at most 64): the operations' view; a test's. */
    public List<String> said() {
        synchronized (said) { return List.copyOf(said); }
    }

    /** The doors whose windows are held now (at most {@value #MOST_DOORS} and the shared one). */
    int doorsHeld() { return windows.size(); }

    /** One refusal: counted; said in full when it opens its door's window (every time with debug on), the closed window summed up. */
    private void refuse(String app, String door, String session, String reason, RuntimeException ruleFailed) {
        counters.refusedNoRoot.incrementAndGet();
        if (debug.getAsBoolean()) { sayInFull(app, door, session, reason, ruleFailed); return; }
        Turn turn = turnAt(door, clock.millis());
        if (turn.more() > 0) sayTheSummary(app, turn);
        if (turn.opensAWindow()) sayInFull(app, door, session, reason, ruleFailed);
    }

    // ── one door's window ──

    private static final class Window {
        long startMs;
        long more;

        Window(long startMs) { this.startMs = startMs; }
    }

    /** What this refusal says: whether it opens a window; the closed window's count to sum up, its door and its start. */
    private record Turn(boolean opensAWindow, String door, long more, long sinceMs) {}

    /** The door's window decides, atomically per door: the first opens it, the rest within it are counted, the first after it closes it. */
    private Turn turnAt(String door, long now) {
        Turn[] turn = new Turn[1];
        windows.compute(heldKeyOf(door), (key, w) -> {
            if (w == null) { turn[0] = new Turn(true, key, 0, now); return new Window(now); }
            if (now - w.startMs >= WINDOW_MS) { turn[0] = new Turn(true, key, w.more, w.startMs); w.startMs = now; w.more = 0; return w; }
            w.more++;
            turn[0] = new Turn(false, key, 0, w.startMs);
            return w;
        });
        return turn[0];
    }

    /** The door's own window while fewer than {@value #MOST_DOORS} are held; a further door shares {@value #OTHER_DOORS}. */
    private String heldKeyOf(String door) {
        return windows.containsKey(door) || windows.size() < MOST_DOORS ? door : OTHER_DOORS;
    }

    // ── the words ──

    private void sayInFull(String app, String door, String session, String reason, RuntimeException ruleFailed) {
        say("[" + app + "] no root at " + door + (session == null ? "" : " (session " + session + ")") + ": " + reason, LogText.cleaned(ruleFailed));
    }

    private void sayTheSummary(String app, Turn turn) {
        say("[" + app + "] … and " + turn.more() + " more refused at " + turn.door() + " in the " + WINDOW_MS / 1000 + " s window from "
            + Instant.ofEpochMilli(turn.sinceMs()) + " (no root)", null);
    }

    /** ONE road for every line: the WARN in the log (a failed rule's stack with it), then the kept lines. */
    private void say(String line, Throwable failure) {
        if (failure != null) log.warn(line, failure); else log.warn(line);
        synchronized (said) {
            said.addLast(line);
            while (said.size() > KEPT_LINES) said.removeFirst();
        }
    }

    private static String doorOf(RoutesphereFacts facts) {
        if (facts == null) return "no facts";
        return facts.ingress() == null ? "no ingress" : LogText.clean(facts.ingress());
    }

    private static String sessionOf(String sessionId) { return sessionId == null ? null : LogText.clean(sessionId); }

    private static String whyOf(RoutesphereFacts facts, RuntimeException ruleFailed) {
        if (ruleFailed != null) return "the rule failed (" + LogText.failureText(ruleFailed) + ")";
        if (facts == null || facts.ingress() == null) return "the facts carry no ingress";
        return "no rule names this door";
    }
}
