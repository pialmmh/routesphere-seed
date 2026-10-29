package com.telcobright.seed.prepaid.api;

import com.telcobright.seed.sessionflow.spi.LedgerPort.Product;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * §15 · The product a request consumes, by the attributes Odoo gives it ("a box of biscuits, a grocery item of 10 KG"): for a kind
 * (video, image), the product whose {@code seconds} is the SMALLEST not below the asked seconds — a 25-second video consumes the 30-second
 * product; a kind with one product and no seconds (an image) answers it for any length; nothing fits → empty, and the lane names no key
 * (cash, where its rule allows). Built once from the catalog's products (a handful per kind), kept by the lane and rebuilt when the
 * catalog's map changes ({@link #over}); each ask is one map get and one ceiling on a few entries — never a scan of the catalog.
 */
public final class ProductIndex {

    private static final Map<String, Product> NONE = Map.of();

    private final Map<String, Product> products;
    private final Map<String, TreeMap<Integer, Product>> timedByKind = new HashMap<>();
    private final Map<String, Product> untimedByKind = new HashMap<>();

    private ProductIndex(Map<String, Product> products) {
        this.products = products;
        for (Product p : products.values()) {
            if (p == null || !p.active() || p.kind() == null) continue;
            String kind = kindOf(p.kind());
            if (p.seconds() == null) untimedByKind.putIfAbsent(kind, p);
            else timedByKind.computeIfAbsent(kind, k -> new TreeMap<>()).putIfAbsent(p.seconds(), p);
        }
    }

    /** The index of these products (by key). */
    public static ProductIndex of(Map<String, Product> products) { return new ProductIndex(products == null ? NONE : products); }

    /** The index over the catalog's CURRENT products: the one given if it was built over the same map, else a new one (the catalog hands out immutable snapshots). */
    public static ProductIndex over(ProductIndex current, Map<String, Product> products) {
        Map<String, Product> now = products == null ? NONE : products;
        return current != null && current.products == now ? current : new ProductIndex(now);
    }

    /** The product of this kind for this many seconds (see the class); empty = none fits. */
    public Optional<Product> productFor(String kind, int seconds) {
        String k = kindOf(kind);
        TreeMap<Integer, Product> timed = timedByKind.get(k);
        Map.Entry<Integer, Product> fit = timed == null ? null : timed.ceilingEntry(Math.max(seconds, 0));
        if (fit != null) return Optional.of(fit.getValue());
        return Optional.ofNullable(untimedByKind.get(k));
    }

    /** The product's key for this kind and length, as {@code productKeys} answers it; empty = no product, so no key. */
    public java.util.List<String> keysFor(String kind, int seconds) {
        return productFor(kind, seconds).map(p -> ProductKeys.exact(p.key())).orElse(java.util.List.of());
    }

    /** How many products the index holds (active, with a kind). */
    public int size() {
        int n = untimedByKind.size();
        for (TreeMap<Integer, Product> timed : timedByKind.values()) n += timed.size();
        return n;
    }

    private static String kindOf(String kind) { return kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT); }
}
