package com.telcobright.seed.sessionflow.spi;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.internal.DirectUnit;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The money of ONE tier, in the call's three verbs: reserve at admission, settle at the end, release when the chain was
 * refused. The call switch implements it on its own ledger ({@code PrepaidServiceWithCompensation}); the ad switch on
 * orchestrix's prepaid roads. The pipeline never knows which.
 *
 * <p><b>Every verb is idempotent per (session id, tier)</b> (P1 §L.1): the base names every reserve {@code <session>#L<tier>} (a later
 * candidate {@code <session>#<try>#L<tier>}, a renewal window {@code …#W<n>}) and settles or releases the tier by that first reference — the
 * same reference twice moves money once. Persistence is async, so a process that dies after the final state's synchronous settle and
 * before the final row lands restores the session from an older row and ends it again: the second {@link #settle} or {@link #release} of
 * the same (session, tier) must move nothing (the in-memory testkit ledger answers its first result; the switch ledger finds the reserve
 * row gone and moves nothing).
 *
 * <ul>
 *   <li>{@link #reserve}: empty = this partner cannot pay it. A present answer = the ledger's own facts.</li>
 *   <li>{@link LedgerFault} (thrown): the ledger did not answer, or answered that IT is broken. The pipeline reports
 *       {@code BILLING_SYSTEM_ERROR}, never a balance cause.</li>
 *   <li>{@link LedgerRefusal} (thrown): the ledger refused for a reason of its own that is not a balance. Its code is
 *       the call's cause.</li>
 * </ul>
 */
public interface LedgerPort {

    /**
     * Hold {@code amount} of the tier's balance under {@code reference}. Called once per tier at admission, and once
     * more per window of a long call ({@code level.getReservationCount()} tells which).
     */
    Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference);

    /**
     * The same reserve, bounded: the answer must come within {@code withinMs} — what is left of the call's admission
     * budget. A ledger that calls a remote road uses the smaller of its own timeout and this, and repeats a timed-out
     * call only when the time left allows it. A ledger that cannot bound its call takes the plain reserve.
     */
    default Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        return reserve(level, amount, reference);
    }

    /**
     * The call ended: it finally costs {@code charged} at this tier. The ledger reconciles against everything the tier
     * reserved ({@code level.getTotalReserved()}) in EITHER direction: the rest goes back, a shortfall is debited.
     * Called exactly once per tier that reserved; the reference is {@code level.getDebitReference()}.
     */
    TierSettlement settle(LevelAdmission level, BigDecimal charged);

    /** Give back everything the tier reserved: a later tier refused, or the candidate was dropped. */
    void release(LevelAdmission level, String why);

    /**
     * A reseller tier was created live under this ledger's root (R10: {@code tenant_created}, kind reseller — prime-context sends it only after
     * the tier's tables are committed): a ledger that keeps books per tier loads the new tier's here — with no money move of its own running
     * meanwhile (never under a session's reserve), the same tier twice one load — BEFORE the root's tree shows the tier to a session. A
     * failure is the ledger's own to say; the tier's money is then refused until it loads. Default: nothing (a ledger that keeps no books per
     * tier — orchestrix's roads, the testkit's).
     */
    default void tierAdded(String tierDb) { }

    /**
     * P1-J · The root's tree was reloaded (a doorbell's ring — a purchase wrote accounts —, a {@code tenant_created}, a root served, the
     * backstop): a ledger that keeps books per tier merges the rows written since its last look — an account made AFTER its tier existed
     * (ad-sphere P2c's G1) —, coalesced and never on a session's thread. Called before the reloaded tree is shown. Default: nothing.
     */
    default void treeReloaded() { }

    /**
     * L2b item 3 · A ring of the root's doorbell {@code config_event_loader_<root>} with its words: {@code what} and change-wire's keys
     * {@code changes} ({@code "tier.table:OPERATION"}). A ledger that keeps a DB of its own reads them (the switch ledger's ledger DB: an
     * INSERT on a table it caches → a reload of new rows, coalesced; a catch-up word → the same; its own tables' UPDATE/DELETE → nothing).
     * Default: nothing.
     */
    default void doorbell(String what, java.util.List<String> changes) { }

    /**
     * After {@link #tierAdded}: are the tier's books loaded? A ledger that keeps books per tier and SAID its load failed (never thrown)
     * answers false, so the kit's gate hands the tier again on the next tree (the next doorbell ring, reload or event) instead of leaving
     * its money refused until a restart. Default: true (a ledger that keeps no books per tier has nothing to load).
     */
    default boolean tierLoaded(String tierDb) { return true; }

    /**
     * L2a · A tier's account as the ledger holds it right now — a read, no write, no lock (the C14 remainder's peek: the whole window was
     * refused, what is left is spent, routesphere-core {@code BalanceBillingService.reserveNextWindowSeconds} 86–120). Empty = this ledger
     * cannot say (orchestrix's roads, a ledger of a lane's own): the renewal then holds whole windows only.
     */
    default Optional<BigDecimal> balanceOf(LevelAdmission level) { return Optional.empty(); }

    /**
     * L2a · CHARGE_NOW — the SMS's final debit (routesphere-core {@code PrepaidServiceWithCompensation.debit} 441–516): {@code amount} taken
     * from the tier's account at once, under its lock, with NO reserve row ("a reserve exists to be settled later, and nothing settles an
     * SMS, so reserving one per message would leave rows behind forever"). Empty = the balance is short, nothing taken. Idempotent per
     * reference: the same reference twice takes once and answers the first. Default: this ledger takes no final charge — refused in words.
     */
    default Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) {
        throw new LedgerFault(getClass().getSimpleName() + " takes no final charge (CHARGE_NOW): the switch ledger does");
    }

    /**
     * L2a · Give back {@code amount} of what {@link #charge} took under the tier's reference ({@code level.getDebitReference()}), once — the
     * SMS's refund (routesphere-core {@code BalanceCheckRule.refund} 236–255: a recharge of the charge). Answers the tier's settlement:
     * charged = what stays taken, returned = {@code amount}. A second refund of the same tier answers the first and moves nothing.
     */
    default TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) {
        throw new LedgerFault(getClass().getSimpleName() + " gives back no final charge (CHARGE_NOW): the switch ledger does");
    }

    /**
     * L2a · The recharge road's credit — {@code amount} (positive) added to the account the level names (its tier's schema, its account id),
     * under the account's lock, a DELTA (routesphere-core {@code PrepaidServiceWithCompensation.recharge} 294–383: "a recharge written
     * DIRECTLY to MySQL is invisible to billing and gets overwritten by the next write-behind"). Default: refused in words.
     */
    default Credited credit(LevelAdmission account, BigDecimal amount, String reference) {
        throw new LedgerFault(getClass().getSimpleName() + " has no credit road: the switch ledger has");
    }

    /** L2a · The account the level names moved onto purchase {@code purchaseId} (its expiry follows the purchase), its balance kept. */
    default Credited reparent(LevelAdmission account, long purchaseId) {
        throw new LedgerFault(getClass().getSimpleName() + " has no reparent road: the switch ledger has");
    }

    /**
     * L2b item 2 · A unit of work over this ledger: ONE business operation over every tier it touches (the hold of every tier at admission,
     * every tier's renewal, every tier's settlement), written as ONE — all tiers or none ({@link LedgerUnit}). Default: a ledger that cannot
     * write a batch applies each move at once and gives back what it took when the unit is not committed (the old saga).
     */
    default LedgerUnit unit() { return new DirectUnit(this); }

    /**
     * L2b item 4 · A purchase made THROUGH the ledger, ONE unit: the purchase row, an account per bucket of the offer with its opening
     * balance (and the purchase's reference row where the ledger keeps them), ids from {@code ids} above what the ledger holds today. The
     * same reference again answers the first purchase, nothing written twice. Default: this ledger has no purchase road — refused in words.
     */
    default Purchased purchase(LevelAdmission buyer, Offer offer, String reference, Ids ids) {
        throw new LedgerFault(getClass().getSimpleName() + " has no purchase road: the switch ledger has (with packagepurchase cached)");
    }

    /**
     * L2b item 4 · The buyer's accounts (its partner, its tier) as THIS ledger holds them — each with its purchase's expiry — read from the
     * ledger's cached purchases (S4: not prime-context's tree). Empty = this ledger cannot say (it caches no purchases): the tree's.
     */
    default Optional<java.util.List<com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount>> accountsOf(LevelAdmission buyer) { return Optional.empty(); }

    /**
     * L3 · The buyer's purchases (its partner, its tier) as THIS ledger holds them, oldest first — read from the ledger's cached purchases
     * (the officer's purchase list; never prime-context's tree). Empty = this ledger cannot say (it caches no purchases).
     */
    default Optional<java.util.List<Purchase>> purchasesOf(LevelAdmission buyer) { return Optional.empty(); }

    /**
     * C1 · The cached ledger of {@code root} — the CRUD of the tables its ledger caches (design §14): a ledger that caches tables answers its
     * own; the kit's ledger of many roots answers the root's. Empty = this ledger caches no tables (orchestrix's roads, the testkit's).
     */
    default Optional<com.telcobright.seed.sessionflow.api.CachedLedger> cachedLedgerOf(String root) { return Optional.empty(); }

    /** L2b item 4 · What one purchase buys (the package catalog is config: prime-context's): its buckets, each an account. */
    record Offer(long packageId, String name, BigDecimal price, java.time.LocalDateTime at, java.time.LocalDateTime expires, java.util.List<Bucket> buckets) {
        public Offer { buckets = buckets == null ? java.util.List.of() : java.util.List.copyOf(buckets); }
    }

    /**
     * One bucket of an offer: an account of this unit with this opening balance — and, since the product items (design §15), the catalog
     * ITEM it is opened for ({@code packageitem.id}; null = an account of no item: a top-up, a row of before). The account carries it
     * ({@code packageaccount.id_packageitem}) so the walk relates the account to its item by key, never by a guess from its unit.
     */
    record Bucket(String uom, BigDecimal opening, Long itemId) {
        public Bucket(String uom, BigDecimal opening) { this(uom, opening, null); }
    }

    // ── §15 · the catalog as the walk reads it: packages, their items by PRODUCT KEY, the products ─────────────────────

    /**
     * §15 (the owner, 2026-10-29: "map prefix = product id … all required entities shall be in the cache, there can't be any db lookup in
     * the hot path") · The tier's catalog as THIS ledger caches it, read by key: a package, its items by their product key
     * ({@code packageitem.prefix} — the call's dialing prefix, the ad's Odoo product id), the package a purchase bought, and the products
     * where the ledger caches them ({@code product_catalog}: a view over Odoo's product tables, or a table). Every answer is a map get on
     * the cache; nothing here reads a database. Empty = this ledger caches no catalog (the walk then matches by unit, as before §15).
     */
    default Optional<ItemCatalog> catalogOf(LevelAdmission tier) { return Optional.empty(); }

    /** §15 · The catalog's reads, every one a hashtable lookup. The maps answered are immutable snapshots. */
    interface ItemCatalog {
        /** The package of this id; empty = not in the catalog. */
        Optional<CatalogPackage> packageOf(long packageId);
        /** The package's items by their product key (the hashtable of step 2); empty = no items or no such package. */
        java.util.Map<String, Item> itemsOf(long packageId);
        /** One item by its id (an account that names its item); empty = unknown. */
        Optional<Item> itemOf(long itemId);
        /** The package a purchase bought; empty = the purchase is not cached here. */
        Optional<Long> packageOfPurchase(long purchaseId);
        /** The products by their key; empty when this ledger caches no products (the call needs none: its key is the prefix itself). */
        java.util.Map<String, Product> products();
        /** One product by its key; empty = none cached under it. */
        default Optional<Product> productOf(String key) { return Optional.ofNullable(key == null ? null : products().get(key.trim())); }
        /** §15.3 · The service groups (the revenue streams) by id → name; empty when this ledger caches none. */
        default java.util.Map<Integer, String> serviceGroups() { return java.util.Map.of(); }
    }

    /** §15 · A package of the catalog: its id, its name, its price, its validity in seconds, whether it is for sale. */
    record CatalogPackage(long id, String name, BigDecimal price, long validitySeconds, boolean active) {}

    /**
     * §15 · One item of a package: {@code key} = {@code packageitem.prefix}, the PRODUCT KEY the walk matches (the call: the dialing
     * prefix "880"; the ad: the Odoo product's id as text); {@code quantity} of {@code uom} is what a purchase opens as the item's account.
     */
    record Item(long id, long packageId, String key, String uom, BigDecimal quantity, int category, String description, Integer serviceGroupId, String kind) {
        /** An item whose key names no product row (the call's prefix): no group, no kind of its own. */
        public Item(long id, long packageId, String key, String uom, BigDecimal quantity, int category, String description) { this(id, packageId, key, uom, quantity, category, description, null, null); }
    }

    /**
     * §15.3 (the owner, 2026-10-30) · A product as the switch caches it ({@code product_catalog}): {@code key} = the product key the items
     * carry; {@code id} = the Odoo variant it came from (null for a product made in the portal); {@code kind} = the SERVICE it belongs to,
     * one of {@link #KINDS} (voice, sms, ad, wifi-internet, …); {@code media} = the ad's attribute (video, image, audio; null for other
     * kinds); {@code seconds} = the duration (a 30-second video), null where there is none; {@code source} = portal | odoo;
     * {@code serviceGroupId} = the revenue stream ({@code enumservicegroup.id}: 30 the ad view, 101 the WiFi, 1..9 the voice groups).
     */
    record Product(String key, Long id, String code, String name, String kind, String media, Integer seconds, String uom, boolean active, String source, Integer serviceGroupId) {
        /** The kinds a product may be: the services of the platform. */
        public static final java.util.List<String> KINDS = java.util.List.of("voice", "sms", "ad", "wifi-internet", "isp-internet", "voice-broadcast", "ivr", "extension");
        public static final java.util.List<String> MEDIA = java.util.List.of("video", "image", "audio");
        public static final java.util.List<String> SOURCES = java.util.List.of("portal", "odoo");
    }

    /** L2b item 4 · Ids from the application's source (seed-idgen's LONG counters): (table, the highest id in use) → a new id above it. */
    @FunctionalInterface
    interface Ids { long next(String table, long highestInUse); }

    /** L3 · One purchase as the ledger holds it: its id, its package, when it was bought, its expiry, its status, its price, its priority. */
    record Purchase(long id, long packageId, java.time.LocalDateTime at, java.time.LocalDateTime expires, String status, BigDecimal price, int priority) {}

    /** What a purchase came to: the purchase's id, its accounts' ids (the offer's order), and whether it was the first answer repeated. */
    record Purchased(long purchaseId, java.util.List<Long> accounts, boolean repeated) {}

    /** What a credit or a reparent came to: the balance before and after, the purchase the account is on now. */
    record Credited(long accountId, BigDecimal before, BigDecimal after, long purchaseId) {}

    /**
     * The longest one reserve may take before this ledger gives up by itself, in milliseconds. 0 = it answers at once
     * (a ledger in the process). The engine says at start how many such answers fit one admission.
     */
    default long slowestAnswerMs() { return 0; }

    /**
     * What a reserve came to.
     *
     * @param account  the ledger's account that holds the reserve (null when the switch named the account itself)
     * @param uom      the unit of that account (null = as rated)
     * @param repeated the ledger had seen this reference before and answered its first result
     */
    record Reservation(Long account, String uom, BigDecimal reserved, BigDecimal balanceBefore, BigDecimal balanceAfter, boolean repeated) {}

    /** The ledger refused with a code of its own: that code is the cause. */
    final class LedgerRefusal extends RuntimeException {
        private final String code;
        public LedgerRefusal(String code, String message) { super(code + ": " + message); this.code = code; }
        public String code() { return code; }
    }

    /** The ledger did not answer, or is broken. */
    final class LedgerFault extends RuntimeException {
        public LedgerFault(String message) { super(message); }
        public LedgerFault(String message, Throwable cause) { super(message, cause); }
    }
}
