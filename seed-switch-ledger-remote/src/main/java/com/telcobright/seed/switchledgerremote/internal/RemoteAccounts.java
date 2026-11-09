package com.telcobright.seed.switchledgerremote.internal;

import com.telcobright.seed.switchledgerremote.api.AccountReads;
import com.telcobright.seed.switchledgerremote.wire.Account;
import com.telcobright.seed.switchledgerremote.wire.AccountRef;
import com.telcobright.seed.switchledgerremote.wire.PurchaseRef;
import com.telcobright.seed.switchledgerremote.wire.TierDb;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** The three account reads over the wire: AccountById, AccountOfPurchase, HeldAccounts. */
public final class RemoteAccounts implements AccountReads {

    private final Calls calls;

    public RemoteAccounts(Calls calls) { this.calls = calls; }

    @Override
    public Optional<Found> accountById(String tierDb, long accountId) {
        AccountRef ref = AccountRef.newBuilder().setDbName(tierDb).setAccountId(accountId).build();
        return found(calls.call("accountById", s -> s.accountById(ref)));
    }

    @Override
    public Optional<Found> accountOf(String tierDb, long purchaseId, Instant now) {
        PurchaseRef ref = PurchaseRef.newBuilder().setDbName(tierDb).setPurchaseId(purchaseId).setAtEpochMs(Instant.EPOCH.equals(now) ? 0 : now.toEpochMilli()).build();
        return found(calls.call("accountOfPurchase", s -> s.accountOfPurchase(ref)));
    }

    @Override
    public Set<Long> heldAccounts(String tierDb) {
        return Set.copyOf(calls.call("heldAccounts", s -> s.heldAccounts(TierDb.newBuilder().setDbName(tierDb).build())).getIdsList());
    }

    private static Optional<Found> found(Account answer) { return WireRows.accountOf(answer).map(a -> new Found(a, a.getExpireDate())); }
}
