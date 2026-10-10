package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.sessionflow.api.TierSettlement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the port answered when a tier closed, by the tier's reference: a settle or a release asked twice answers its first result and moves
 * nothing ({@code LedgerPort}: "every verb is idempotent by its reference"). Bounded: the oldest answers go when it is full — a tier settled
 * twice that long apart has no row to move anyway.
 */
final class ClosedTiers {

    private static final int KEPT = 100_000;

    private final Map<String, TierSettlement> byTier = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, TierSettlement> eldest) { return size() > KEPT; }
    };

    synchronized TierSettlement get(String tier) { return byTier.get(tier); }

    synchronized TierSettlement remember(String tier, TierSettlement done) {
        byTier.put(tier, done);
        return done;
    }
}
