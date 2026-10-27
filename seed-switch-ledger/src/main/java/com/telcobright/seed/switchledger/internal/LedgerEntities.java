package com.telcobright.seed.switchledger.internal;

import com.telcobright.core.cache.CacheableEntity;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
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

    /** One entity the ledger knows: its name in the MemLedger, its class, its table. */
    public record Known(String name, Class<? extends CacheableEntity<?, ?>> type, String table) {}

    private static final List<Known> KNOWN = List.of(
        new Known(Books.ACCOUNT, PackageAccount.class, "packageaccount"),
        new Known(Books.RESERVE, PackageAccountReserve.class, "packageaccountreserve"),
        new Known(Books.CHARGE, ChargeReference.class, "packageaccountcharge"),
        new Known(Books.PURCHASE, LedgerPurchase.class, "packagepurchase"));

    private LedgerEntities() {}

    /** Every known entity, in its order. */
    public static List<Known> known() { return KNOWN; }

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

    private static boolean hasAGeneratedKey(Class<?> type) {
        for (java.lang.reflect.Field f : type.getDeclaredFields()) {
            if (f.isAnnotationPresent(jakarta.persistence.Id.class) && f.isAnnotationPresent(jakarta.persistence.GeneratedValue.class)) return true;
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
