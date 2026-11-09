package com.telcobright.seed.switchledgerremote.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledgerremote.api.AccountReads;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The three reads of the WiFi ({@code LedgerAccounts}) over the engine's MemLedger: an account by its id, the account of a purchase
 * (unexpired at {@code now} unless {@code now} is the epoch — by the PURCHASE's expiry where the ledger caches purchases, else by the
 * row's own), the account ids with an open reserve row. Plain reads of the cache, as the WiFi reads it today; a scan per call for the
 * purchase (the cache has no index by purchase — said in the WiFi's own words, a B2 item at 10^5).
 */
public final class MemLedgerReads implements AccountReads {

    private final MemLedger ledger;
    private final boolean keepsPurchases;
    private final ZoneId zone;

    /** @param held the entity names the ledger holds ({@code Books.PURCHASE} among them = the purchase's expiry rules) */
    public MemLedgerReads(MemLedger ledger, Set<String> held, ZoneId zone) {
        this.ledger = ledger;
        this.keepsPurchases = held.contains(Books.PURCHASE);
        this.zone = zone;
    }

    @Override
    public Optional<Found> accountById(String tierDb, long accountId) {
        PackageAccount a = ledger.getEntity(tierDb, Books.ACCOUNT, accountId);
        return a == null ? Optional.empty() : Optional.of(new Found(a, expiryOf(tierDb, a)));
    }

    @Override
    public Optional<Found> accountOf(String tierDb, long purchaseId, Instant now) {
        Map<Long, PackageAccount> all = ledger.getAllEntities(tierDb, Books.ACCOUNT);
        if (all == null) return Optional.empty();
        LocalDateTime at = Instant.EPOCH.equals(now) ? null : LocalDateTime.ofInstant(now, zone);
        LocalDateTime expires = keepsPurchases ? purchaseExpiry(tierDb, purchaseId) : null;
        return all.values().stream()
            .filter(a -> a.getIdpackagePurchase() != null && a.getIdpackagePurchase() == purchaseId)
            .filter(a -> at == null || !expiredAt(a, expires, at))
            .findFirst()
            .map(a -> new Found(a, expires != null ? expires : a.getExpireDate()));
    }

    @Override
    public Set<Long> heldAccounts(String tierDb) {
        Map<String, PackageAccountReserve> rows = ledger.getAllEntities(tierDb, Books.RESERVE);
        Set<Long> out = new HashSet<>();
        if (rows != null) for (PackageAccountReserve r : rows.values()) if (r != null && r.getIdPackageAccount() != null) out.add(r.getIdPackageAccount());
        return out;
    }

    /** Expired by the purchase's expiry where known (the cache's purchase row), else by the account row's own. */
    private static boolean expiredAt(PackageAccount a, LocalDateTime purchaseExpiry, LocalDateTime at) {
        if (purchaseExpiry != null) return !purchaseExpiry.isAfter(at);
        return a.isExpiredAt(at);
    }

    private LocalDateTime expiryOf(String tierDb, PackageAccount a) {
        LocalDateTime own = a.getExpireDate();
        if (!keepsPurchases || a.getIdpackagePurchase() == null) return own;
        LocalDateTime purchase = purchaseExpiry(tierDb, a.getIdpackagePurchase());
        return purchase != null ? purchase : own;
    }

    private LocalDateTime purchaseExpiry(String tierDb, long purchaseId) {
        LedgerPurchase p = ledger.getEntity(tierDb, Books.PURCHASE, purchaseId);
        return p == null ? null : p.getExpireDate();
    }
}
