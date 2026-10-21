package com.telcobright.seed.sessionflow.samples.bed;

import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Route;
import com.telcobright.statewalk.session.SessionHandler;
import com.telcobright.statewalk.session.SessionTimings;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SessionStart;
import com.telcobright.statewalk.session.events.SignalingFailed;
import com.telcobright.statewalk.session.events.SignalingProgress;

/**
 * The minimal handler a signaling machine is tested under, alone (P1 §H, item 2): no tenant, no money, no record — every call written
 * down, the decisions read from the {@link Script}. A machine that passes here speaks the supervisor's contract; the base is not needed.
 */
public final class ScriptedHandler implements SessionHandler<BedContext> {

    private final Script s;

    public ScriptedHandler(Script script) { this.s = script; }

    @Override public SessionTimings timings() { return s.timings; }

    @Override public void onStart(BedContext c, SessionStart start) { s.calls.add("onStart:" + start.facts().sessionId()); }

    @Override public Verdict admit(BedContext c) { s.calls.add("admit"); return s.verdict; }

    @Override
    public Route nextHop(BedContext c) {
        s.calls.add("nextHop:" + c.hop);
        return c.hop < s.plan.size() ? s.plan.get(c.hop) : null;
    }

    @Override
    public boolean retry(BedContext c, SignalingFailed f) {
        s.calls.add("retry:" + f.cause());
        return switch (s.onFailure.getOrDefault(f.cause(), Script.OnFailure.FAIL)) {
            case RETRY_SAME -> true;
            case REROUTE -> ++c.hop < s.plan.size();
            case FAIL -> false;
        };
    }

    @Override public void onProgress(BedContext c, SignalingProgress p) { s.calls.add("onProgress:" + p.phase()); }
    @Override public void onEstablished(BedContext c, Established e) { s.calls.add("onEstablished:" + e.remoteParty()); }
    @Override public void onActive(BedContext c) { s.calls.add("onActive"); }
    @Override public void onEnd(BedContext c, ServiceEnd e) { s.calls.add("onEnd:" + e.cause() + ":" + e.endedBy()); }
    @Override public void onFailed(BedContext c, SignalingFailed f) { s.calls.add("onFailed:" + f.cause() + ":" + f.code()); }

    /** The real service time, answer → end, in ms: what a flow would charge. */
    @Override
    public void settle(BedContext c, Outcome o) {
        long serviceMs = c.answer == null ? 0 : Math.max(0, c.endedAtMs - c.answer.atMs());
        s.calls.add("settle:" + o + ":" + serviceMs);
    }

    @Override public void release(BedContext c) { s.calls.add("release"); }

    @Override public void record(BedContext c, Outcome o) { s.calls.add("record:" + o + ":" + c.endCause); }
}
