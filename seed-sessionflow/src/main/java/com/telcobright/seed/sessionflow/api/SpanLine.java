package com.telcobright.seed.sessionflow.api;

/**
 * ONE closed span as the session's persisted context keeps it (O4 + §L.6): the span's number, its tier's picture ({@link TierLine}: the
 * account it ran on, what it reserved under which reference), its settlement, its wall-clock. The live {@link ClosedSpan} is rebuilt from
 * it on the root's snapshot after a restore — so the next rotation numbers its series after the last span (never a repeated reference),
 * the CDR keeps every span's record, and the live span's seconds stay its own.
 */
public record SpanLine(int spanNo, TierLine tier, TierSettlement settlement, long startedAtMs, long endedAtMs) {

    /** The picture of a live closed span. */
    public static SpanLine of(ClosedSpan span) {
        return new SpanLine(span.spanNo(), TierLine.of(span.level()), span.settlement(), span.startedAtMs(), span.endedAtMs());
    }

    /** The live closed span again, on the root's snapshot. */
    public ClosedSpan toSpan(RootTree tree) {
        return new ClosedSpan(spanNo, tier.toLevel(tree), settlement, startedAtMs, endedAtMs);
    }
}
