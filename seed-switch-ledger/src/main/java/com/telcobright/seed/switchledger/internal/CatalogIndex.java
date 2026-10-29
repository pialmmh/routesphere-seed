package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.CatalogPackage;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Item;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Product;
import com.telcobright.seed.switchledger.api.LedgerPackage;
import com.telcobright.seed.switchledger.api.LedgerPackageItem;
import com.telcobright.seed.switchledger.api.LedgerProduct;
import com.telcobright.seed.switchledger.api.LedgerPurchase;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * §15 · The catalog of ONE tier as the walk reads it — every read a map get on an immutable snapshot made from the cache: packages by id,
 * each package's items by their PRODUCT KEY ({@code packageitem.prefix}), items by id, the package of each purchase, the products by key.
 * Made on the first ask of a tier (off the session's path: the first admission, an admin road) and DROPPED when new rows were merged (a
 * reload, a tier added, a catalog write through the CRUD) — the next ask makes it again. Nothing here reads a database.
 */
final class CatalogIndex {

    private final Books books;
    private final Map<String, Snapshot> byTier = new ConcurrentHashMap<>();

    CatalogIndex(Books books) { this.books = books; }

    /** The tier's catalog; empty when this ledger caches no catalog. */
    Optional<LedgerPort.ItemCatalog> of(String db) {
        if (!books.keepsCatalog() || db == null) return Optional.empty();
        return Optional.of(byTier.computeIfAbsent(db, this::snapshot));
    }

    /** New rows were merged somewhere: every tier's snapshot is made again on its next ask. */
    void forget() { byTier.clear(); }

    private Snapshot snapshot(String db) {
        Map<Long, CatalogPackage> packages = new HashMap<>();
        for (LedgerPackage p : books.ledger().<Long, LedgerPackage>getAllEntities(db, Books.PACKAGE).values()) packages.put(p.getId(), packageOf(p));
        Map<Long, Map<String, Item>> itemsByPackage = new HashMap<>();
        Map<Long, Item> itemsById = new HashMap<>();
        for (LedgerPackageItem row : books.ledger().<Long, LedgerPackageItem>getAllEntities(db, Books.PACKAGE_ITEM).values()) {
            Item item = itemOf(row);
            itemsById.put(item.id(), item);
            itemsByPackage.computeIfAbsent(item.packageId(), k -> new HashMap<>()).merge(item.key(), item, CatalogIndex::lowerId);   // a repeated key: the lower id keeps it
        }
        itemsByPackage.replaceAll((k, v) -> Map.copyOf(v));
        Map<Long, Long> packageOfPurchase = new HashMap<>();
        if (books.keepsPurchases()) {
            for (LedgerPurchase p : books.ledger().<Long, LedgerPurchase>getAllEntities(db, Books.PURCHASE).values()) packageOfPurchase.put(p.getId(), p.getIdPackage());
        }
        Map<String, Product> products = new HashMap<>();
        if (books.keepsProducts()) {
            for (LedgerProduct p : books.ledger().<String, LedgerProduct>getAllEntities(db, Books.PRODUCT).values()) products.put(p.getProductKey(), productOf(p));
        }
        return new Snapshot(Map.copyOf(packages), Map.copyOf(itemsByPackage), Map.copyOf(itemsById), Map.copyOf(packageOfPurchase), Map.copyOf(products));
    }

    static CatalogPackage packageOf(LedgerPackage p) {
        return new CatalogPackage(p.getId(), p.getName(), p.getBasePrice() == null ? BigDecimal.ZERO : p.getBasePrice(),
            p.getValidity() == null ? 0 : p.getValidity(), Boolean.TRUE.equals(p.getActiveStatus()));
    }

    /** The item's key is its prefix, trimmed: the hashtable's key is what the lane asks with ({@code ProductKeys} trims the same way). */
    static Item itemOf(LedgerPackageItem row) {
        return new Item(row.getId(), row.getIdPackage() == null ? 0 : row.getIdPackage(), keyOf(row.getPrefix()), row.getIdUom(),
            row.getQuantity() == null ? BigDecimal.ZERO : row.getQuantity(), row.getCategory() == null ? 0 : row.getCategory(), row.getDescription());
    }

    static Product productOf(LedgerProduct p) {
        return new Product(keyOf(p.getProductKey()), p.getCode(), p.getName(), p.getKind() == null ? null : p.getKind().trim().toLowerCase(java.util.Locale.ROOT),
            p.getSeconds(), p.getUom(), !Boolean.FALSE.equals(p.getActive()));
    }

    static String keyOf(String prefix) { return prefix == null ? "" : prefix.trim(); }

    /** Two items of one package with the same key (a data slip): the one made first — the lower id — is the item; the cache's order says nothing. */
    private static Item lowerId(Item a, Item b) { return a.id() <= b.id() ? a : b; }

    /** One tier's immutable catalog. */
    private record Snapshot(Map<Long, CatalogPackage> packages, Map<Long, Map<String, Item>> itemsByPackage, Map<Long, Item> itemsById,
                            Map<Long, Long> packageOfPurchase, Map<String, Product> products) implements LedgerPort.ItemCatalog {
        @Override public Optional<CatalogPackage> packageOf(long packageId) { return Optional.ofNullable(packages.get(packageId)); }
        @Override public Map<String, Item> itemsOf(long packageId) { return itemsByPackage.getOrDefault(packageId, Map.of()); }
        @Override public Optional<Item> itemOf(long itemId) { return Optional.ofNullable(itemsById.get(itemId)); }
        @Override public Optional<Long> packageOfPurchase(long purchaseId) { return Optional.ofNullable(packageOfPurchase.get(purchaseId)); }
    }
}
