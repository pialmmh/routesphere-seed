package com.telcobright.seed.switchledgerremote.internal;

import com.telcobright.seed.switchledger.api.Credit;
import com.telcobright.seed.switchledgerremote.api.CreditDoor;
import com.telcobright.seed.switchledgerremote.wire.CapRequest;
import com.telcobright.seed.switchledgerremote.wire.CreditRequest;
import com.telcobright.seed.switchledgerremote.wire.ReparentRequest;
import com.telcobright.seed.switchledgerremote.wire.Tier;

import java.math.BigDecimal;

/** The credit door over the wire: Credit, Reparent, CapAt — an unheld account comes back as the door's own {@code IllegalArgumentException}. */
public final class RemoteCredit implements CreditDoor {

    private final Calls calls;

    public RemoteCredit(Calls calls) { this.calls = calls; }

    @Override
    public Credit.Credited credit(String dbName, long accountId, BigDecimal delta, String reference) {
        CreditRequest request = CreditRequest.newBuilder().setAccount(account(dbName, accountId)).setAmount(Wire.text(delta)).setReference(Wire.orEmpty(reference)).build();
        return Wire.doorCreditedOf(calls.door("credit", s -> s.credit(request)));
    }

    @Override
    public Credit.Credited reparent(String dbName, long accountId, long purchaseId) {
        ReparentRequest request = ReparentRequest.newBuilder().setAccount(account(dbName, accountId)).setPurchaseId(purchaseId).build();
        return Wire.doorCreditedOf(calls.door("reparent", s -> s.reparent(request)));
    }

    @Override
    public Credit.Credited capAt(String dbName, long accountId, BigDecimal ceiling, String reference) {
        CapRequest request = CapRequest.newBuilder().setAccount(account(dbName, accountId)).setCeiling(Wire.text(ceiling)).setReference(Wire.orEmpty(reference)).build();
        return Wire.doorCreditedOf(calls.door("capAt", s -> s.capAt(request)));
    }

    private static Tier account(String dbName, long accountId) { return Tier.newBuilder().setDbName(dbName).setPackageAccountId(accountId).build(); }
}
