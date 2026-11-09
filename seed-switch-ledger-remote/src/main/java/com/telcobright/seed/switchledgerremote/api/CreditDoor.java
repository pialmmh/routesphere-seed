package com.telcobright.seed.switchledgerremote.api;

import com.telcobright.seed.switchledger.api.Credit;

import java.math.BigDecimal;

/**
 * The credit door of design §3.3 — the three moves outside a session that {@link Credit} makes on the embedded engine (a top-up delta, a
 * reparent to a new purchase, the WiFi clock's cap), served over the wire by the same words. An account the schema does not hold is an
 * {@code IllegalArgumentException} (NOT_FOUND on the wire), as {@link Credit} throws it; a cap is a ceiling, never a recorded move: the same
 * reference twice caps twice.
 */
public interface CreditDoor {

    Credit.Credited credit(String dbName, long accountId, BigDecimal delta, String reference);

    Credit.Credited reparent(String dbName, long accountId, long purchaseId);

    Credit.Credited capAt(String dbName, long accountId, BigDecimal ceiling, String reference);

    /** The embedded engine's own door. */
    static CreditDoor of(Credit credit) {
        return new CreditDoor() {
            @Override public Credit.Credited credit(String dbName, long accountId, BigDecimal delta, String reference) { return credit.credit(dbName, accountId, delta, reference); }
            @Override public Credit.Credited reparent(String dbName, long accountId, long purchaseId) { return credit.reparent(dbName, accountId, purchaseId); }
            @Override public Credit.Credited capAt(String dbName, long accountId, BigDecimal ceiling, String reference) { return credit.capAt(dbName, accountId, ceiling, reference); }
        };
    }
}
