package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the switch ledger answered, by reference — the memory that makes every verb idempotent ({@code LedgerPort}: "every verb is
 * idempotent by its reference"): the first answer of every hold until its tier closes, the closed tiers, the final charges. A unit writes
 * here only AFTER its record is in the WAL (a unit refused remembers nothing).
 */
final class Answers {

    /** The first answer of every reference held, until its tier closes. */
    private final Map<String, Reservation> answered = new ConcurrentHashMap<>();
    /** The references of every open tier (its row key), to forget at the close. */
    private final Map<String, Set<String>> referencesOf = new ConcurrentHashMap<>();
    private final ClosedTiers closed = new ClosedTiers();
    /** L2a · CHARGE_NOW: the first answer of every charge, by its reference (bounded as the closed tiers are). */
    private final Charges charges = new Charges();

    Reservation hold(String reference) { return answered.get(reference); }

    void rememberHold(String rowKey, String reference, Reservation held) {
        answered.put(reference, held);
        referencesOf.computeIfAbsent(rowKey, k -> ConcurrentHashMap.newKeySet()).add(reference);
    }

    TierSettlement closedTier(String tier) { return closed.get(tier); }

    void close(String tier, TierSettlement done) {
        forget(tier);
        closed.remember(tier, done);
    }

    void forget(String tier) {
        Set<String> references = referencesOf.remove(tier);
        if (references != null) references.forEach(answered::remove);
    }

    Reservation charge(String reference) { return charges.get(reference); }

    void rememberCharge(String reference, Reservation done) { charges.remember(reference, done); }

    static Reservation repeatOf(Reservation first) {
        return new Reservation(first.account(), first.uom(), first.reserved(), first.balanceBefore(), first.balanceAfter(), true);
    }
}
