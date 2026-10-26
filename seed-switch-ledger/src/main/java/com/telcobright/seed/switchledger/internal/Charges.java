package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L2a · What the port answered for a final charge (CHARGE_NOW), by its reference: the same charge asked twice answers its first result and
 * takes nothing more ({@code LedgerPort}: "every verb is idempotent by its reference"). Bounded as {@link ClosedTiers} is: the oldest
 * answers go when it is full — a session is admitted once, so a repeat that far apart does not come.
 */
final class Charges {

    private static final int KEPT = 100_000;

    private final Map<String, Reservation> byReference = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Reservation> eldest) { return size() > KEPT; }
    };

    synchronized Reservation get(String reference) { return byReference.get(reference); }

    synchronized Reservation remember(String reference, Reservation done) {
        byReference.put(reference, done);
        return done;
    }
}
