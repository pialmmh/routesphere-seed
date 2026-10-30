package com.telcobright.seed.prepaid.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.idgen.api.IdKind;
import com.telcobright.seed.idgen.spi.IdSource;
import com.telcobright.seed.sessionflow.api.CachedLedger;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.api.LedgerProduct;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
 *   <li>{@link #balance} — one account's live balance;</li>
 *   <li>{@link #purchases} — a partner's purchases as the ledger caches them (L3: the officer's purchase list).</li>
 * </ul>
 * Each refuses in words: a tier not in the root's tree, an account the tier's ledger does not hold, a delta of 0 or less, a purchase with
 * no buckets or no id source, a ledger with no purchase road.
 *
 * <p><b>The application's setting (L2c).</b> A purchase's ids come from bucket-next, the sharded id service, through seed-idgen's client —
 * the application builds it from ONE value of ITS OWN config and hands it in; the seed has no config file and names no service (R0):
 * <pre>
 *   IdSource ids = BucketNext.builder()
 *       .shards("http://10.10.199.21:7001,http://10.10.198.21:7001")   // EVERY shard's base URL, comma-separated; no tenant here
 *       .timeout(Duration.ofSeconds(2))
 *       .build();
 *   PrepaidAdmin admin = prepaid.admin().withIds(ids);
 * </pre>
 * Like Kafka's server list, but EVERY shard is named: a shard does not tell about the others (shard k of N hands out k, k+N, k+2N, …; the
 * client asks them in turn, skips one that is down, and fails closed when none answers — nothing is minted then, the purchase writes
 * nothing). The ids are minted ABOVE the highest the ledger holds ({@code mintAbove}), so a table that existed before keeps its ids.
 *
 * <p><b>The tenant on every mint (ARCH-0100; the owner, 2026-10-30: one id service per installation, the tenant mandatory on every call).</b>
 * The id service is tenant-first ({@code /api/v2/{tenant}/…}, one counter per (tenant, entity)) and the client has no default tenant:
 * every mint of a purchase carries <b>the root's tenant id</b> — the {@code root} word of the call ({@code purchase(root, tierDb, …)}),
 * tenant-master's tenant id, the same word as the root schema and the topic suffix ({@code btcl} on the bed). The tier ({@code tierDb})
 * is never the tenant: a reseller's accounts are minted under its root's tenant, in the root's counters.
 *
 * <p><b>The one-creator rule.</b> The ledger writes these ids into the database as they are (the ledger DB names the account's
 * {@code @GeneratedValue} key as minted by the caller — mem-ledger's {@code keysMintedByTheCaller}): in a lane on this engine nothing else
 * creates accounts (or purchases) in those tables by the database's counter — no portal INSERT, no auto-increment; every creator takes
 * its ids from the same bucket-next cluster.
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

    /**
     * The same roads, purchases taking their ids from the application's source: its seed-idgen client over EVERY bucket-next shard
     * ({@code BucketNext.builder().shards(<one config value: every shard's URL, comma-separated>)}), a test's {@code InMemoryIdSource}.
     */
    public PrepaidAdmin withIds(IdSource ids) { return new PrepaidAdmin(kit, Objects.requireNonNull(ids, "ids")); }

    // ── purchase ────────────────────────────────────────────────────────────

    /** A package bought by {@code partnerId} at {@code tierDb} of {@code root}: ONE unit through the tier's ledger; the same reference → the first. */
    public LedgerPort.Purchased purchase(String root, String tierDb, int partnerId, LedgerPort.Offer offer, String reference) {
        refuseABadPurchase(offer, reference);
        LevelAdmission buyer = buyerAt(tierOf(root, tierDb), partnerId);
        return kit.ledger().purchase(buyer, offer, reference, (table, highestInUse) -> nextId(root, table, highestInUse));
    }

    /**
     * §15 · The offer of a CATALOG package at {@code tierDb} of {@code root}, bought at {@code at}: the package's name and price, its validity
     * from {@code at}, ONE bucket per item — the item's unit and quantity, the item NAMED ({@code id_packageitem}) so the walk relates the
     * account to its product by key. Refused in words when the tier's ledger caches no catalog, or the package is unknown, not for sale,
     * or has no items. The caller then buys it: {@code purchase(root, tierDb, partnerId, offerOf(...), reference)}.
     */
    public LedgerPort.Offer offerOf(String root, String tierDb, long packageId, LocalDateTime at) {
        Objects.requireNonNull(at, "at");
        Tenant tier = tierOf(root, tierDb);
        LedgerPort.ItemCatalog catalog = kit.ledger().catalogOf(new LevelAdmission(0, tier, null, null)).orElseThrow(() -> new IllegalStateException(
            "the ledger of the tier '" + tierDb + "' of the root '" + root + "' caches no catalog: name package and packageitem among its ledger DB's entities"));
        LedgerPort.CatalogPackage pkg = catalog.packageOf(packageId).orElseThrow(() -> new IllegalArgumentException("package " + packageId + " is not in the catalog of the tier '" + tierDb + "'"));
        if (!pkg.active()) throw new IllegalArgumentException("package " + packageId + " (" + pkg.name() + ") is not for sale");
        List<LedgerPort.Item> items = new ArrayList<>(catalog.itemsOf(packageId).values());
        items.sort(Comparator.comparingLong(LedgerPort.Item::id));                                   // the items' order = the accounts' order
        List<LedgerPort.Bucket> buckets = new ArrayList<>(items.size());
        for (LedgerPort.Item item : items) buckets.add(new LedgerPort.Bucket(item.uom(), item.quantity(), item.id()));
        if (buckets.isEmpty()) throw new IllegalArgumentException("package " + packageId + " (" + pkg.name() + ") has no items: a purchase would open no account");
        return new LedgerPort.Offer(packageId, pkg.name(), pkg.price(), at, at.plusSeconds(pkg.validitySeconds()), buckets);
    }

    // ── §15.3 · products through the cached ledger (the portal's CRUD; the Odoo import takes the same road) ─────────────────

    /** The tier's products as the cache holds them (active and not), by key. */
    public List<LedgerPort.Product> products(String root, String tierDb) {
        List<LedgerPort.Product> products = new ArrayList<>(catalogOf(root, tierDb).products().values());
        products.sort(Comparator.comparing(LedgerPort.Product::key));
        return List.copyOf(products);
    }

    /** The tier's service groups (id → name) as the cache holds them; empty where the ledger caches none. */
    public java.util.Map<Integer, String> serviceGroups(String root, String tierDb) { return catalogOf(root, tierDb).serviceGroups(); }

    /**
     * A product made at {@code tierDb} of {@code root}: ONE cached-ledger unit, refused in words by the ledger's rules (a key taken, a kind
     * outside the enum, a source outside portal | odoo, an ad product without its media, seconds on an image, a group the tier does not
     * know) — the cache holds it at once, the database follows. {@code product.active()} false makes it dormant from the start.
     */
    public LedgerPort.Product createProduct(String root, String tierDb, LedgerPort.Product product, String reference) {
        Objects.requireNonNull(product, "product");
        List<String> keys = ProductKeys.exact(product.key());
        if (keys.isEmpty()) throw new IllegalArgumentException("a product carries its key (the Odoo variant's id as text, or the portal's own)");
        String key = keys.get(0);
        if (catalogOf(root, tierDb).productOf(key).isPresent()) throw new IllegalArgumentException("product " + key + " is in the catalog of " + tierDb + " already");
        commit(root, tierDb, reference, unit -> unit.insert(tierDb, rowOf(product, key)));
        return catalogOf(root, tierDb).productOf(key).orElseThrow();
    }

    /** The fields given of a product (name, kind, media, seconds, uom, code, serviceGroupId, active — the entity's field names), in ONE unit; refused in words. */
    public LedgerPort.Product updateProduct(String root, String tierDb, String key, java.util.Map<String, ?> fields, String reference) {
        String k = requireProduct(root, tierDb, key);
        if (fields == null || fields.isEmpty()) throw new IllegalArgumentException("nothing to change on product " + k);
        if (fields.containsKey("productKey") || fields.containsKey("odooProductId") || fields.containsKey("id") || fields.containsKey("source")) throw new IllegalArgumentException("a product's key, its Odoo id and its source never change: make another product");
        commit(root, tierDb, reference, unit -> unit.update(tierDb, LedgerProduct.class, k, fields));
        return catalogOf(root, tierDb).productOf(k).orElseThrow();
    }

    /** A product taken off sale (never deleted: an item may name it); an item that names it keeps paying from the accounts already opened. */
    public LedgerPort.Product deactivateProduct(String root, String tierDb, String key, String reference) {
        return updateProduct(root, tierDb, key, java.util.Map.of("active", Boolean.FALSE), reference);
    }

    private String requireProduct(String root, String tierDb, String key) {
        List<String> keys = ProductKeys.exact(key);
        if (keys.isEmpty()) throw new IllegalArgumentException("a product is named by its key");
        if (catalogOf(root, tierDb).productOf(keys.get(0)).isEmpty()) throw new IllegalArgumentException("product " + keys.get(0) + " is not in the catalog of " + tierDb);
        return keys.get(0);
    }

    private void commit(String root, String tierDb, String reference, java.util.function.Consumer<CachedLedger.Unit> stage) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("a product write carries its reference (the officer's action)");
        tierOf(root, tierDb);
        CachedLedger crud = kit.ledger().cachedLedgerOf(root).orElseThrow(() -> new IllegalStateException("the root '" + root + "' has no cached ledger: products are written through it only"));
        CachedLedger.Unit unit = crud.unit(reference);
        stage.accept(unit);
        CachedLedger.Outcome outcome = unit.commit();
        if (outcome instanceof CachedLedger.Refused refused) throw new IllegalArgumentException(refused.reason());
    }

    private LedgerPort.ItemCatalog catalogOf(String root, String tierDb) {
        return kit.ledger().catalogOf(new LevelAdmission(0, tierOf(root, tierDb), null, null)).orElseThrow(() -> new IllegalStateException(
            "the ledger of the tier '" + tierDb + "' of the root '" + root + "' caches no catalog: name package, packageitem and product among its ledger DB's entities"));
    }

    private static LedgerProduct rowOf(LedgerPort.Product p, String key) {
        LedgerProduct row = new LedgerProduct();
        row.setProductKey(key);
        row.setOdooProductId(p.id());
        row.setCode(p.code());
        row.setName(p.name());
        row.setKind(p.kind());
        row.setMedia(p.media());
        row.setSeconds(p.seconds());
        row.setUom(p.uom());
        row.setActive(p.active());
        row.setSource(p.source() == null ? "portal" : p.source());
        row.setServiceGroupId(p.serviceGroupId());
        return row;
    }

    /** One id of {@code table} above what the ledger holds, under the ROOT's tenant (the root word: the tenant id = the root schema). */
    private long nextId(String rootTenant, String table, long highestInUse) {
        return Long.parseLong(ids.mintAbove(rootTenant, table, IdKind.LONG, 1, highestInUse).get(0));
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

    /** The purchases of {@code partnerId} at {@code tierDb} as the ledger holds them, oldest first; empty when the ledger caches no purchases. */
    public List<LedgerPort.Purchase> purchases(String root, String tierDb, int partnerId) {
        return kit.ledger().purchasesOf(buyerAt(tierOf(root, tierDb), partnerId)).orElse(List.of());
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
