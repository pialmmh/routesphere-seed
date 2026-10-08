package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;

/**
 * One reserve series a tier closed mid-session (O4, the WiFi's B17 rotation): the account that could fund no more, settled for
 * everything it held, with the span's own wall-clock. The CDR writes it as a record of its own, {@code <sid>.<spanNo>}, on the same
 * tier and partner, with its account, its charge and its seconds; the tiers above do not split.
 */
public record ClosedSpan(int spanNo, LevelAdmission level, TierSettlement settlement, long startedAtMs, long endedAtMs) {
    public double seconds() { return Math.max(0, endedAtMs - startedAtMs) / 1000.0; }
}
