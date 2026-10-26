package com.telcobright.seed.prepaid.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.idgen.api.IdKind;
import com.telcobright.seed.idgen.spi.IdSource;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.LedgerPort;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The money roads of a lane's Prepaid (L2a item 5, L2b items 4 and 5; design §10 {@code prepaid.admin()}; P20): what an operator's or a
 * reseller's screen calls on the switch that owns the root — never prime-context, never config-manager, never a database write (the owner,
 * 2026-10-26: package money goes through mem-ledger). Plain Java: the application mounts them on its own roads (the seed has no server, R0).
 *
 * <ul>
 *   <li>{@link #purchase} — a package bought THROUGH the ledger: ONE unit (the purchase, an account per bucket with its opening balance,
 *       its reference row); ids from the application's {@link IdSource} ({@link #withIds}: seed-idgen LONG counters above what the ledger
 *       holds; R0: no default service url here); the same reference again → the first purchase, nothing written twice;</li>
 *   <li>{@link #credit} — a top-up: a DELTA added to one account under its lock (routesphere-core {@code recharge} 294–383); {@link #reparent}
 *       — onto a new purchase, the balance kept;</li>
 *   <li>{@link #accounts} — a partner's accounts as the LEDGER names them (its cached purchases, their expiry: S4) where it caches
 *       purchases, else as the tree names them; each with its live balance;</li>
 *   <li>{@link #balance} — one account's live balance.</li>
 * </ul>
 * Each refuses in words: a tier not in the root's tree, an account the tier's ledger does not hold, a delta of 0 or less, a purchase with
 * no buckets or no id source, a ledger with no purchase road.
 */
public final class PrepaidAdmin {

    /** One account of a partner: id, purchase, unit, name, expiry (the purchase's) and the ledger's live balance (null = it cannot say). */
    public record Account(long id, long purchaseId, String uom, String name, java.time.LocalDateTime expiresAt, BigDecimal balance) {}

    private final RoutesphereKit kit;
    private final IdSource ids;

    PrepaidAdmin(RoutesphereKit kit) { this(kit, null); }

    private PrepaidAdmin(RoutesphereKit kit, IdSource ids) {
        this.kit = Objects.requireNonNull(kit, "kit");
        this.ids = ids;
    }

    /** The same roads, purchases taking their ids from the application's source (its seed-idgen client; a test's InMemoryIdSource). */
    public PrepaidAdmin withIds(IdSource ids) { return new PrepaidAdmin(kit, Objects.requireNonNull(ids, "ids")); }

    // ── purchase ────────────────────────────────────────────────────────────

    /** A package bought by {@code partnerId} at {@code tierDb} of {@code root}: ONE unit through the tier's ledger; the same reference → the first. */
    public LedgerPort.Purchased purchase(String root, String tierDb, int partnerId, LedgerPort.Offer offer, String reference) {
        refuseABadPurchase(offer, reference);
        return kit.ledger().purchase(buyerAt(tierOf(root, tierDb), partnerId), offer, reference, this::nextId);
    }

    private long nextId(String table, long highestInUse) {
        return Long.parseLong(ids.mintAbove(table, IdKind.LONG, 1, highestInUse).get(0));
    }

    private void refuseABadPurchase(LedgerPort.Offer offer, String reference) {
        if (ids == null) throw new IllegalStateException("a purchase needs ids: the application passes its seed-idgen IdSource (admin().withIds(source)) — the seed names no id service of its own (R0)");
        if (offer == null || offer.buckets().isEmpty()) throw new IllegalArgumentException("a purchase buys at least one bucket (an account with its opening balance): " + offer);
        if (offer.expires() == null || offer.at() == null) throw new IllegalArgumentException("a purchase has its date and its expiry: " + offer);
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("a purchase carries its reference (a repeat answers the first)");
    }

    // ── credit · reparent ───────────────────────────────────────────────────

    /** Add {@code amount} (positive) to an account of {@code tierDb} in {@code root}'s tree; refused in words (an unknown tier, a delta of 0 or less). */
    public LedgerPort.Credited credit(String root, String tierDb, long accountId, BigDecimal amount, String reference) {
        return kit.ledger().credit(accountOf(root, tierDb, accountId), amount, reference);
    }

    /** Move an account of {@code tierDb} onto purchase {@code purchaseId}; its balance stays. */
    public LedgerPort.Credited reparent(String root, String tierDb, long accountId, long purchaseId) {
        return kit.ledger().reparent(accountOf(root, tierDb, accountId), purchaseId);
    }

    // ── accounts · balance ──────────────────────────────────────────────────

    /** The accounts of {@code partnerId} at {@code tierDb} (expired ones included), as the ledger names them, else the tree; each with its live balance. */
    public List<Account> accounts(String root, String tierDb, int partnerId) {
        Tenant tier = tierOf(root, tierDb);
        Optional<List<PackageAccount>> fromTheLedger = kit.ledger().accountsOf(buyerAt(tier, partnerId));
        List<PackageAccount> rows = fromTheLedger.orElseGet(() -> fromTheTree(tier, partnerId));
        List<Account> accounts = new ArrayList<>(rows.size());
        for (PackageAccount row : rows) accounts.add(accountWithBalance(tier, row));
        return List.copyOf(accounts);
    }

    /** One account's live balance; refused in words when the tier's ledger does not hold it. */
    public BigDecimal balance(String root, String tierDb, long accountId) {
        return kit.ledger().balanceOf(accountOf(root, tierDb, accountId)).orElseThrow(() -> new IllegalArgumentException(
            "account " + accountId + " is not held by the ledger of the tier '" + tierDb + "' of the root '" + root + "'"));
    }

    private static List<PackageAccount> fromTheTree(Tenant tier, int partnerId) {
        Map<Long, List<PackageAccount>> byPartner = tier.getContext() == null ? null : tier.getContext().getPartnerIdWisePackageAccounts();
        List<PackageAccount> rows = byPartner == null ? null : byPartner.get((long) partnerId);
        return rows == null ? List.of() : rows;
    }

    private Account accountWithBalance(Tenant tier, PackageAccount row) {
        BigDecimal live = kit.ledger().balanceOf(levelFor(tier, row.getId())).orElse(null);
        return new Account(row.getId(), row.getIdpackagePurchase(), row.getUom(), row.getName(), row.getExpireDate(), live);
    }

    private LevelAdmission accountOf(String root, String tierDb, long accountId) { return levelFor(tierOf(root, tierDb), accountId); }

    /** The account as the ledger port names it: the tier (its tenant routes a root's ledger) and the account id. */
    private static LevelAdmission levelFor(Tenant tier, long accountId) {
        LevelAdmission level = new LevelAdmission(0, tier, new Partner(), null);
        level.setChargeAccountId(accountId);
        return level;
    }

    /** The buyer as the ledger port names it: its tier and its partner. */
    private static LevelAdmission buyerAt(Tenant tier, int partnerId) {
        Partner partner = new Partner();
        partner.setIdPartner(partnerId);
        return new LevelAdmission(0, tier, partner, null);
    }

    private Tenant tierOf(String root, String tierDb) {
        return kit.tenants().tenantByDbName(root, tierDb).orElseThrow(() -> new IllegalArgumentException(
            "the tier '" + tierDb + "' is not in the tree of the root '" + root + "' this switch serves"));
    }
}
