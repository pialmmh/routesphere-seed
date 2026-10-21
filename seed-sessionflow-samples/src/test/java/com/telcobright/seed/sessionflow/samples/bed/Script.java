package com.telcobright.seed.sessionflow.samples.bed;

import com.telcobright.statewalk.session.Route;
import com.telcobright.statewalk.session.SessionTimings;
import com.telcobright.statewalk.session.Verdict;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the minimal handler answers, scripted per test (the statewalk SIP example's {@code CallRules} shape): the verdict, the plan,
 * what a failure's cause does. Shared by every session of the bed — test configuration, never per-session state; it also keeps every
 * context it made so a test reads them after the machines went back to their pools.
 */
public final class Script {

    /** What a {@code SignalingFailed}'s cause does: the next hop, the same hop again, or nothing (FAILED). */
    public enum OnFailure { REROUTE, RETRY_SAME, FAIL }

    public volatile SessionTimings timings = new SessionTimings(5, 2, 30, 3600);
    public volatile Verdict verdict = Verdict.ok();
    public volatile List<? extends Route> plan = List.of();
    public final Map<String, OnFailure> onFailure = new ConcurrentHashMap<>();

    /** Every handler call, in order, across sessions. */
    public final List<String> calls = new CopyOnWriteArrayList<>();
    /** Every supervisor context the kit made, in order (one per session). */
    public final List<BedContext> contexts = new CopyOnWriteArrayList<>();

    public BedContext newContext() {
        BedContext c = new BedContext();
        contexts.add(c);
        return c;
    }

    public Script plan(Route... hops) { this.plan = List.of(hops); return this; }

    public Script on(String cause, OnFailure what) { onFailure.put(cause, what); return this; }

    public BedContext last() { return contexts.get(contexts.size() - 1); }

    /** The calls from the first one that starts with {@code prefix} to the end. */
    public List<String> from(String prefix) {
        for (int i = 0; i < calls.size(); i++) if (calls.get(i).startsWith(prefix)) return List.copyOf(calls.subList(i, calls.size()));
        throw new AssertionError("no call " + prefix + " in " + calls);
    }

    public long count(String prefix) { return calls.stream().filter(c -> c.startsWith(prefix)).count(); }
}
