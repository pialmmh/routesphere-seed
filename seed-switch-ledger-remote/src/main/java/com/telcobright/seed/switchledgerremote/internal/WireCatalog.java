package com.telcobright.seed.switchledgerremote.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.CatalogPackage;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Item;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Product;
import com.telcobright.seed.switchledger.api.LedgerPackage;
import com.telcobright.seed.switchledger.api.LedgerPackageItem;
import com.telcobright.seed.switchledger.api.LedgerProduct;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import com.telcobright.seed.switchledger.api.LedgerServiceGroup;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledgerremote.wire.CatalogItem;
import com.telcobright.seed.switchledgerremote.wire.CatalogProduct;
import com.telcobright.seed.switchledgerremote.wire.CatalogSnapshot;
import com.telcobright.seed.switchledgerremote.wire.PurchasePackage;
import com.telcobright.seed.switchledgerremote.wire.ServiceGroup;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.telcobright.seed.switchledgerremote.internal.Wire.decimal;
import static com.telcobright.seed.switchledgerremote.internal.Wire.nullIfEmpty;
import static com.telcobright.seed.switchledgerremote.internal.Wire.orEmpty;
import static com.telcobright.seed.switchledgerremote.internal.Wire.text;

/**
 * §15 on the wire. The server sends the catalog of a tier as ROWS ({@code CatalogSnapshot}: packages, items with their product key, the
 * products, the service groups, the package of each purchase); the client builds the maps the walk reads — the same way
 * {@code CatalogIndex} builds them from the cache (an item's key is its prefix trimmed; an item carries its product's service group and
 * kind; two items of one package with one key: the lower id keeps it; a product's kind, media and source are lower-cased), so the catalog
 * a {@code RemoteSwitchLedger} answers is the one the embedded port answers. {@link #snapshotOf(MemLedger, String, Set)} is the dump from
 * a cache that holds the catalog (the twice-run proof); the service dumps the same rows from PostgreSQL by JDBC (Q7).
 */
public final class WireCatalog {

    private WireCatalog() {}

    /** No catalog for the tier. */
    public static CatalogSnapshot none() { return CatalogSnapshot.newBuilder().setPresent(false).build(); }

    // ── the client: rows → the walk's maps ──────────────────────────────────

    /** The tier's catalog as the walk reads it; empty when the server holds none. */
    public static Optional<LedgerPort.ItemCatalog> catalogOf(CatalogSnapshot s) {
        if (!s.getPresent()) return Optional.empty();
        Map<String, Product> products = new HashMap<>();
        for (CatalogProduct p : s.getProductsList()) products.put(keyOf(p.getKey()), productOf(p));
        Map<Integer, String> groups = new HashMap<>();
        for (ServiceGroup g : s.getServiceGroupsList()) groups.put(g.getId(), nullIfEmpty(g.getName()));
        Map<Long, CatalogPackage> packages = new HashMap<>();
        for (com.telcobright.seed.switchledgerremote.wire.CatalogPackage p : s.getPackagesList()) {
            packages.put(p.getId(), new CatalogPackage(p.getId(), nullIfEmpty(p.getName()), decimalOrZero(p.getPrice()), p.getValiditySeconds(), p.getActive()));
        }
        Map<Long, Map<String, Item>> itemsByPackage = new HashMap<>();
        Map<Long, Item> itemsById = new HashMap<>();
        for (CatalogItem row : s.getItemsList()) {
            Item item = itemOf(row, products.get(keyOf(row.getKey())));
            itemsById.put(item.id(), item);
            itemsByPackage.computeIfAbsent(item.packageId(), k -> new HashMap<>()).merge(item.key(), item, WireCatalog::lowerId);
        }
        itemsByPackage.replaceAll((k, v) -> Map.copyOf(v));
        Map<Long, Long> packageOfPurchase = new HashMap<>();
        for (PurchasePackage p : s.getPurchasePackagesList()) packageOfPurchase.put(p.getPurchaseId(), p.getPackageId());
        return Optional.of(new Snapshot(Map.copyOf(packages), Map.copyOf(itemsByPackage), Map.copyOf(itemsById), Map.copyOf(packageOfPurchase), Map.copyOf(products), Map.copyOf(groups)));
    }

    /** As {@code CatalogIndex.itemOf}: the key trimmed, the product's group and kind on the item (null where its key names no product). */
    private static Item itemOf(CatalogItem row, Product product) {
        return new Item(row.getId(), row.getPackageId(), keyOf(row.getKey()), nullIfEmpty(row.getUom()), decimalOrZero(row.getQuantity()), row.getCategory(),
            nullIfEmpty(row.getDescription()), product == null ? null : product.serviceGroupId(), product == null ? null : product.kind());
    }

    /** As {@code CatalogIndex.productOf}: kind, media and source lower-cased; active unless said false. */
    private static Product productOf(CatalogProduct p) {
        return new Product(keyOf(p.getKey()), p.hasId() ? p.getId() : null, nullIfEmpty(p.getCode()), nullIfEmpty(p.getName()), lower(p.getKind()), lower(p.getMedia()),
            p.hasSeconds() ? p.getSeconds() : null, nullIfEmpty(p.getUom()), p.getActive(), lower(p.getSource()), p.hasServiceGroupId() ? p.getServiceGroupId() : null);
    }

    private static BigDecimal decimalOrZero(String text) { BigDecimal d = decimal(text); return d == null ? BigDecimal.ZERO : d; }

    private static String lower(String s) { return s == null || s.isEmpty() ? null : s.trim().toLowerCase(Locale.ROOT); }

    static String keyOf(String prefix) { return prefix == null ? "" : prefix.trim(); }

    private static Item lowerId(Item a, Item b) { return a.id() <= b.id() ? a : b; }

    /** One tier's immutable catalog, the shape of {@code CatalogIndex.Snapshot}. */
    private record Snapshot(Map<Long, CatalogPackage> packages, Map<Long, Map<String, Item>> itemsByPackage, Map<Long, Item> itemsById,
                            Map<Long, Long> packageOfPurchase, Map<String, Product> products, Map<Integer, String> serviceGroups) implements LedgerPort.ItemCatalog {
        @Override public Optional<CatalogPackage> packageOf(long packageId) { return Optional.ofNullable(packages.get(packageId)); }
        @Override public Map<String, Item> itemsOf(long packageId) { return itemsByPackage.getOrDefault(packageId, Map.of()); }
        @Override public Optional<Item> itemOf(long itemId) { return Optional.ofNullable(itemsById.get(itemId)); }
        @Override public Optional<Long> packageOfPurchase(long purchaseId) { return Optional.ofNullable(packageOfPurchase.get(purchaseId)); }
    }

    // ── the server: a cache that holds the catalog → rows ──────────────────

    /**
     * The tier's catalog rows from a MemLedger that caches them ({@code held} = the entity names it holds, {@code Books.PACKAGE} among them;
     * else {@link #none()}): the twice-run proof's server. The service reads the same rows from PostgreSQL instead (Q7: the catalog outside
     * the cache) and fills the same message.
     */
    public static CatalogSnapshot snapshotOf(MemLedger ledger, String db, Set<String> held) {
        if (db == null || !held.contains(Books.PACKAGE)) return none();
        CatalogSnapshot.Builder out = CatalogSnapshot.newBuilder().setPresent(true);
        for (LedgerPackage p : ledger.<Long, LedgerPackage>getAllEntities(db, Books.PACKAGE).values()) {
            out.addPackages(com.telcobright.seed.switchledgerremote.wire.CatalogPackage.newBuilder().setId(p.getId()).setName(orEmpty(p.getName())).setPrice(text(p.getBasePrice()))
                .setValiditySeconds(p.getValidity() == null ? 0 : p.getValidity()).setActive(Boolean.TRUE.equals(p.getActiveStatus())));
        }
        if (held.contains(Books.PACKAGE_ITEM)) {
            for (LedgerPackageItem i : ledger.<Long, LedgerPackageItem>getAllEntities(db, Books.PACKAGE_ITEM).values()) {
                out.addItems(CatalogItem.newBuilder().setId(i.getId()).setPackageId(i.getIdPackage() == null ? 0 : i.getIdPackage()).setKey(orEmpty(i.getPrefix()))
                    .setUom(orEmpty(i.getIdUom())).setQuantity(text(i.getQuantity())).setCategory(i.getCategory() == null ? 0 : i.getCategory()).setDescription(orEmpty(i.getDescription())));
            }
        }
        if (held.contains(Books.PRODUCT)) for (LedgerProduct p : ledger.<String, LedgerProduct>getAllEntities(db, Books.PRODUCT).values()) out.addProducts(productRowOf(p));
        if (held.contains(Books.SERVICE_GROUP)) {
            for (LedgerServiceGroup g : ledger.<Integer, LedgerServiceGroup>getAllEntities(db, Books.SERVICE_GROUP).values()) {
                out.addServiceGroups(ServiceGroup.newBuilder().setId(g.getId()).setName(orEmpty(g.getName())));
            }
        }
        if (held.contains(Books.PURCHASE)) {
            for (LedgerPurchase p : ledger.<Long, LedgerPurchase>getAllEntities(db, Books.PURCHASE).values()) {
                out.addPurchasePackages(PurchasePackage.newBuilder().setPurchaseId(p.getId()).setPackageId(p.getIdPackage() == null ? 0 : p.getIdPackage()));
            }
        }
        return out.build();
    }

    private static CatalogProduct.Builder productRowOf(LedgerProduct p) {
        CatalogProduct.Builder row = CatalogProduct.newBuilder().setKey(orEmpty(p.getProductKey())).setCode(orEmpty(p.getCode())).setName(orEmpty(p.getName()))
            .setKind(orEmpty(p.getKind())).setMedia(orEmpty(p.getMedia())).setUom(orEmpty(p.getUom())).setActive(!Boolean.FALSE.equals(p.getActive())).setSource(orEmpty(p.getSource()));
        if (p.getOdooProductId() != null) row.setId(p.getOdooProductId());
        if (p.getSeconds() != null) row.setSeconds(p.getSeconds());
        if (p.getServiceGroupId() != null) row.setServiceGroupId(p.getServiceGroupId());
        return row;
    }
}
