package com.telcobright.seed.switchledger.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Bucket;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Ids;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Offer;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Purchase;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Purchased;
import com.telcobright.seed.switchledger.api.ChargeReference;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L2b item 4 · Purchases THROUGH the ledger (design §5.3, decision 2) and the partner's accounts read from the ledger's cached purchases
 * (S4). A purchase is ONE unit: the purchase row, an account per bucket of the offer with its opening balance, and — where the ledger keeps
 * references ({@code packageaccountcharge}) — its reference row {@code purchase:<reference>} (its {@code id_packageaccount} = the PURCHASE's
 * id), so a repeated reference answers the first purchase even after a restart; without that table, the first answers are remembered in
 * memory (bounded). Ids come from the application's source above what the ledger holds today (one creator per table, S7) — and the
 * database gets those very ids (L2c): the ledger DB names the account's {@code @GeneratedValue} key as minted by the caller
 * ({@code LedgerDb.register}, mem-ledger's {@code keysMintedByTheCaller}), so every INSERT writes the id the account carries.
 *
 * <p>The partner → accounts index of a tier is made from the cache on its first ask (off the session's path: the admin roads, the first
 * admission of the tier), kept up to date by the purchases made here, and dropped when new rows were merged (a reload, a tier added) or an
 * account moved to another purchase.
 */
final class Purchases {

    private static final Logger log = LoggerFactory.getLogger(Purchases.class);
    private static final int KEPT = 100_000;
    static final String REFERENCE_UOM = "PURCHASE";

    private final Books books;
    private final Map<String, Purchased> answered = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Purchased> eldest) { return size() > KEPT; }
    };
    /** tier → partner → its accounts' ids (made from the cache on the first ask). */
    private final Map<String, Map<Long, List<Long>>> accountsByPartner = new ConcurrentHashMap<>();

    Purchases(Books books) { this.books = books; }

    /** ONE unit: the purchase, its accounts, its reference row; the same reference again → the first answer, nothing written. */
    synchronized Purchased purchase(LevelAdmission buyer, Offer offer, String reference, Ids ids) {
        refuseWithoutPurchases(buyer);
        String db = buyer.getDbName(), key = "purchase:" + reference;
        Purchased first = firstAnswer(db, key, reference);
        if (first != null) return new Purchased(first.purchaseId(), first.accounts(), true);
        UnitOfWork unit = books.unit();
        Purchased made = stage(unit, buyer, offer, key, ids);
        unit.commit("make purchase " + made.purchaseId() + " of partner " + buyer.getPartnerId() + " in " + db + " (" + reference + ")");
        answered.put(reference, made);
        Map<Long, List<Long>> index = accountsByPartner.get(db);                        // made from the cache later if not made yet (it has them now)
        if (index != null) index.computeIfAbsent((long) buyer.getPartnerId(), p -> new ArrayList<>()).addAll(made.accounts());
        log.info("PURCHASE {} | partner {} of {}: purchase {} with accounts {} until {}", LogText.clean(reference), buyer.getPartnerId(), db, made.purchaseId(), made.accounts(), offer.expires());
        return made;
    }

    private Purchased stage(UnitOfWork unit, LevelAdmission buyer, Offer offer, String key, Ids ids) {
        String db = buyer.getDbName();
        long purchaseId = ids.next("packagepurchase", books.highestId(Books.PURCHASE));
        books.insertPurchase(unit, db, LedgerPurchase.made(purchaseId, offer.packageId(), buyer.getPartnerId(), offer.at(), offer.expires(), offer.price()));
        List<Long> accounts = new ArrayList<>();
        long highestAccount = books.highestId(Books.ACCOUNT);
        for (Bucket bucket : offer.buckets()) {
            long id = ids.next("packageaccount", Math.max(highestAccount, accounts.isEmpty() ? 0 : accounts.get(accounts.size() - 1)));
            books.openAccount(unit, db, id, purchaseId, offer.name() + "-" + bucket.uom(), bucket.uom(), bucket.opening() == null ? BigDecimal.ZERO : bucket.opening());
            accounts.add(id);
        }
        if (books.keepsCharges()) {
            unit.insert(db, Books.CHARGE, key, new ChargeReference(key, purchaseId, REFERENCE_UOM, offer.price() == null ? BigDecimal.ZERO : offer.price(),
                BigDecimal.ZERO, BigDecimal.ZERO, null, books.now()));
        }
        return new Purchased(purchaseId, List.copyOf(accounts), false);
    }

    /** The first answer of this reference: remembered, or — after a restart — its reference row and the purchase's accounts in the cache. */
    private Purchased firstAnswer(String db, String key, String reference) {
        Purchased seen = answered.get(reference);
        if (seen != null || !books.keepsCharges()) return seen;
        ChargeReference row = books.ledger().getEntity(db, Books.CHARGE, key);
        if (row == null) return null;
        long purchaseId = row.getIdPackageAccount();
        List<Long> accounts = new ArrayList<>();
        for (PackageAccount a : books.ledger().<Long, PackageAccount>getAllEntities(db, Books.ACCOUNT).values()) {
            if (a.getIdpackagePurchase() != null && a.getIdpackagePurchase() == purchaseId) accounts.add(a.getId());
        }
        accounts.sort(Long::compare);
        return new Purchased(purchaseId, List.copyOf(accounts), false);
    }

    // ── S4 · the partner's accounts from the ledger's purchases ─────────────

    /**
     * The buyer's accounts at its tier, each a COPY with its purchase's expiry; empty = this ledger caches no purchases. L3 S6 — as
     * prime-context serves the catalog ({@code AllCacheLoader.buildPartnerAccountMap}): the accounts of ACTIVE purchases only (a cancelled
     * or suspended purchase never pays), in prime-context's order — the operator's priority ({@code onSelectPriority} > 0), then the
     * soonest expiry, then the older purchase, then the lower purchase id; an account's own id last. Expired purchases stay listed with
     * their expiry (the walk skips them, the officer sees them).
     */
    Optional<List<PackageAccount>> accountsOf(LevelAdmission buyer) {
        if (!books.keepsPurchases() || buyer.getDbName() == null) return Optional.empty();
        String db = buyer.getDbName();
        List<Long> ids = indexOf(db).getOrDefault((long) buyer.getPartnerId(), List.of());
        List<Held> held = new ArrayList<>(ids.size());
        for (Long id : List.copyOf(ids)) {
            PackageAccount live = books.liveAccount(db, id);
            LedgerPurchase purchase = live == null || live.getIdpackagePurchase() == null ? null : books.ledger().getEntity(db, Books.PURCHASE, live.getIdpackagePurchase());
            if (purchase != null && "ACTIVE".equalsIgnoreCase(purchase.getStatus())) held.add(new Held(withItsExpiry(live, purchase), purchase));
        }
        held.sort(PRIME_CONTEXTS_ORDER);
        return Optional.of(held.stream().map(Held::account).toList());
    }

    /** An account with its purchase, for the order. */
    private record Held(PackageAccount account, LedgerPurchase purchase) {}

    /** prime-context's order of use ({@code AllCacheLoader.comparePurchases}), then the account's id. */
    private static final Comparator<Held> PRIME_CONTEXTS_ORDER = Comparator.<Held>comparingInt(h -> priorityOrLast(h.purchase()))
        .thenComparing(h -> h.purchase().getExpireDate(), Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(h -> h.purchase().getPurchaseDate(), Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(h -> h.purchase().getId(), Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(h -> h.account().getId(), Comparator.nullsLast(Comparator.naturalOrder()));

    /** A positive onSelectPriority is the operator's manual override; unset or not positive, the purchase sorts after every override. */
    private static int priorityOrLast(LedgerPurchase purchase) {
        Integer priority = purchase.getOnSelectPriority();
        return priority != null && priority > 0 ? priority : Integer.MAX_VALUE;
    }

    /** L3 · The buyer's purchases at its tier from the cache, oldest first (an officer's read, off the session's path); empty = none cached. */
    Optional<List<Purchase>> purchasesOf(LevelAdmission buyer) {
        if (!books.keepsPurchases() || buyer.getDbName() == null) return Optional.empty();
        long partner = buyer.getPartnerId();
        List<Purchase> purchases = new ArrayList<>();
        for (LedgerPurchase p : books.ledger().<Long, LedgerPurchase>getAllEntities(buyer.getDbName(), Books.PURCHASE).values()) {
            if (p.getIdPartner() != null && p.getIdPartner() == partner) purchases.add(purchaseOf(p));
        }
        purchases.sort(Comparator.comparingLong(Purchase::id));
        return Optional.of(List.copyOf(purchases));
    }

    private static Purchase purchaseOf(LedgerPurchase p) {
        return new Purchase(p.getId(), p.getIdPackage() == null ? 0 : p.getIdPackage(), p.getPurchaseDate(), p.getExpireDate(), p.getStatus(),
            p.getPrice(), p.getPriority() == null ? 0 : p.getPriority());
    }

    /** New rows merged, a tier added, an account moved: the index of every tier made again on its next ask. */
    void forgetTheIndex() { accountsByPartner.clear(); }

    private Map<Long, List<Long>> indexOf(String db) { return accountsByPartner.computeIfAbsent(db, this::indexFromTheCache); }

    private Map<Long, List<Long>> indexFromTheCache(String db) {
        Map<Long, Long> partnerOfPurchase = new HashMap<>();
        for (LedgerPurchase p : books.ledger().<Long, LedgerPurchase>getAllEntities(db, Books.PURCHASE).values()) partnerOfPurchase.put(p.getId(), p.getIdPartner());
        Map<Long, List<Long>> index = new ConcurrentHashMap<>();
        for (PackageAccount a : books.ledger().<Long, PackageAccount>getAllEntities(db, Books.ACCOUNT).values()) {
            Long partner = a.getIdpackagePurchase() == null ? null : partnerOfPurchase.get(a.getIdpackagePurchase());
            if (partner != null) index.computeIfAbsent(partner, p -> new ArrayList<>()).add(a.getId());
        }
        index.values().forEach(list -> list.sort(Long::compare));
        return index;
    }

    private static PackageAccount withItsExpiry(PackageAccount live, LedgerPurchase purchase) {
        PackageAccount copy = RowFields.copyOf(live);                                   // C2b: every field, its class kept
        copy.setExpireDate(purchase == null ? null : purchase.getExpireDate());
        return copy;
    }

    private void refuseWithoutPurchases(LevelAdmission buyer) {
        if (!books.keepsPurchases()) {
            throw new LedgerFault("the ledger of " + buyer.getDbName() + " caches no purchases: name packagepurchase in its ledger DB (ledger.entities) to make purchases through it");
        }
    }
}
