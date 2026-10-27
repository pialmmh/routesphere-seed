package com.telcobright.seed.switchledger.internal;

import com.telcobright.core.cache.CacheableEntity;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.switchledger.api.ChargeReference;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import com.telcobright.seed.switchledger.dependencies.LedgerDbSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * L2b item 3 · The entities a ledger DB can cache, by name and table, and the ones a root's settings name (an entity name, or a prefix of
 * its table: {@code package} = all of them). A word that names nothing, or a set without the account and the reserve (the switch ledger's
 * two), is refused in words.
 */
public final class LedgerEntities {

    /** One entity the ledger knows: its name in the MemLedger, its class, its table, the shape its table holds. */
    public record Known(String name, Class<? extends CacheableEntity<?, ?>> type, String table, Shape shape) {}

    /**
     * C2 · What the table's columns hold (prime-context's DDL, its PostgreSQL and MySQL twins; the narrower where they differ): the fields
     * that may not be empty, the longest text, the digits of a number. A row outside it would be refused by the database at the
     * write-behind — and a batch the database refuses stops the WAL consumer (mem-ledger: after 10 failures in a row) — so the cached
     * ledger refuses it first ({@link RowRules}). The key is not listed: it is minted or named before.
     */
    public record Shape(Set<String> required, Map<String, Integer> textLimits, Map<String, Digits> digits, Set<String> unique) {
        static final Shape NONE = new Shape(Set.of(), Map.of(), Map.of(), Set.of());
    }

    /** A number column's digits: {@code precision} in all, {@code scale} after the point. */
    public record Digits(int precision, int scale) {}

    private static final Digits BALANCE = new Digits(20, 6), PRICE = new Digits(10, 2);

    private static final List<Known> KNOWN = List.of(
        new Known(Books.ACCOUNT, com.telcobright.seed.switchledger.api.LedgerAccount.class, "packageaccount", new Shape(
            Set.of("idpackagePurchase", "lastAmount", "balanceBefore", "balanceAfter", "uom"),
            Map.of("name", 255, "uom", 45),
            Map.of("lastAmount", BALANCE, "balanceBefore", BALANCE, "balanceAfter", BALANCE, "creditLimit", BALANCE),
            Set.of())),
        new Known(Books.RESERVE, PackageAccountReserve.class, "packageaccountreserve", Shape.NONE),
        new Known(Books.CHARGE, ChargeReference.class, "packageaccountcharge", Shape.NONE),
        new Known(Books.PURCHASE, LedgerPurchase.class, "packagepurchase", new Shape(
            Set.of("idPackage", "idPartner", "purchaseDate", "expireDate", "status", "paid", "autoRenewalStatus", "price", "vat", "ait", "priority", "discount"),
            Map.of("status", 50, "paymentRef", 64),
            Map.of("price", PRICE, "vat", PRICE, "ait", PRICE),
            Set.of("paymentRef"))));

    private LedgerEntities() {}

    /** Every known entity, in its order. */
    public static List<Known> known() { return KNOWN; }

    /**
     * C1 · The known entity of this class; empty = the ledger knows no such entity. C2b: a persistent parent of the cached class names it
     * too — {@code PackageAccount} is the cached {@code LedgerAccount}'s parent, and the rows the ledger answers are of the cached class.
     */
    public static java.util.Optional<Known> ofType(Class<?> type) {
        java.util.Optional<Known> exact = KNOWN.stream().filter(k -> k.type().equals(type)).findFirst();
        if (exact.isPresent() || type == null || !type.isAnnotationPresent(jakarta.persistence.Entity.class)) return exact;
        return KNOWN.stream().filter(k -> type.isAssignableFrom(k.type())).findFirst();
    }

    /** C2 · The known entity of this table (any case); empty = none. */
    public static java.util.Optional<Known> ofTable(String table) { return KNOWN.stream().filter(k -> k.table().equalsIgnoreCase(table == null ? "" : table.trim())).findFirst(); }

    /** C2 · The known entity of this MemLedger name; empty = none. */
    static java.util.Optional<Known> ofName(String name) { return KNOWN.stream().filter(k -> k.name().equals(name)).findFirst(); }

    /** The entities the settings name — name → known — in the known order; refused in words when a word names nothing or a must is missing. */
    public static Map<String, Known> named(LedgerDbSettings settings) {
        Map<String, Known> named = new LinkedHashMap<>();
        for (String word : settings.entities()) {
            List<Known> matched = matching(word);
            if (matched.isEmpty()) throw new IllegalStateException("the ledger DB's entity '" + word + "' names no known entity or table (known: " + tables() + ")");
            for (Known k : KNOWN) if (matched.contains(k)) named.put(k.name(), k);
        }
        for (String must : Set.of(Books.ACCOUNT, Books.RESERVE)) {
            if (!named.containsKey(must)) throw new IllegalStateException("the switch ledger's DB caches " + Books.ACCOUNT + " and " + Books.RESERVE + " at least; "
                + settings.entities() + " leaves out " + must);
        }
        for (String table : settings.cacheOnly()) {
            if (named.values().stream().noneMatch(k -> k.table().equalsIgnoreCase(table))) {
                throw new IllegalStateException("the ledger DB's cache-only table '" + table + "' is not one it caches (" + named.keySet() + ")");
            }
        }
        return named;
    }

    /**
     * L2c · The entities among these whose key the DATABASE would generate ({@code @Id} with {@code @GeneratedValue}: rtc-domain's
     * {@code PackageAccount.id} is {@code IDENTITY}). A ledger DB names them as keys minted by the caller (mem-ledger's
     * {@code keysMintedByTheCaller}): the seed mints those ids (the application's {@code IdSource}) and the ledger writes them, so a
     * purchase's accounts get in the database the very ids the cache holds. The purchase's row ({@link LedgerPurchase}) and the reference
     * row ({@link ChargeReference}) carry no generated key: their keys are written already.
     */
    public static List<Class<? extends CacheableEntity>> keysTheDatabaseWouldGenerate(java.util.Collection<Known> entities) {
        List<Class<? extends CacheableEntity>> generated = new ArrayList<>();
        for (Known k : entities) if (hasAGeneratedKey(k.type())) generated.add(k.type());
        return generated;
    }

    /** The key, declared by the class or a parent it extends (C2b: {@code LedgerAccount}'s key is rtc-domain's {@code PackageAccount}'s). */
    private static boolean hasAGeneratedKey(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(jakarta.persistence.Id.class) && f.isAnnotationPresent(jakarta.persistence.GeneratedValue.class)) return true;
            }
        }
        return false;
    }

    /** The tables the settings cache (lower case). */
    public static Set<String> tablesOf(Map<String, Known> named) {
        Set<String> tables = new java.util.LinkedHashSet<>();
        for (Known k : named.values()) tables.add(k.table());
        return tables;
    }

    /** A word that IS an entity's name or table names that one; otherwise every table it is a prefix of ({@code package}). */
    private static List<Known> matching(String word) {
        String w = word.trim().toLowerCase(Locale.ROOT);
        List<Known> out = new ArrayList<>();
        for (Known k : KNOWN) if (k.name().equalsIgnoreCase(w) || k.table().equals(w)) out.add(k);
        if (!out.isEmpty()) return out;
        for (Known k : KNOWN) if (k.table().startsWith(w)) out.add(k);
        return out;
    }

    private static List<String> tables() { return KNOWN.stream().map(Known::table).toList(); }
}
