package com.telcobright.seed.prepaid.api;

import com.telcobright.seed.sessionflow.spi.LedgerPort.Product;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * §15 · The product a request consumes, by the attributes Odoo gives it ("a box of biscuits, a grocery item of 10 KG"): for a kind and a
 * media (ad + video), the product whose {@code seconds} is the SMALLEST not below the asked seconds — a 25-second video consumes the
 * 30-second product; a kind + media with one untimed product (ad + image) answers it for any length; nothing fits → empty, and the lane
 * names no key (cash, where its rule allows). Built once from the catalog's products (a handful per kind), kept by the lane and rebuilt
 * when the catalog's map changes ({@link #over}); each ask is one map get and one ceiling on a few entries — never a scan of the catalog.
 */
public final class ProductIndex {

    private static final Map<String, Product> NONE = Map.of();

    private final Map<String, Product> products;
    private final Map<String, TreeMap<Integer, Product>> timed = new HashMap<>();
    private final Map<String, Product> untimed = new HashMap<>();

    private ProductIndex(Map<String, Product> products) {
        this.products = products;
        for (Product p : products.values()) {
            if (p == null || !p.active() || p.kind() == null) continue;
            String slot = slotOf(p.kind(), p.media());
            if (p.seconds() == null) untimed.putIfAbsent(slot, p);
            else timed.computeIfAbsent(slot, k -> new TreeMap<>()).putIfAbsent(p.seconds(), p);
        }
    }

    /** The index of these products (by key). */
    public static ProductIndex of(Map<String, Product> products) { return new ProductIndex(products == null ? NONE : products); }

    /** The index over the catalog's CURRENT products: the one given if it was built over the same map, else a new one (the catalog hands out immutable snapshots). */
    public static ProductIndex over(ProductIndex current, Map<String, Product> products) {
        Map<String, Product> now = products == null ? NONE : products;
        return current != null && current.products == now ? current : new ProductIndex(now);
    }

    /** The product of this kind and media for this many seconds (see the class); media null = the kind's products without media (voice, sms); empty = none fits. */
    public Optional<Product> productFor(String kind, String media, int seconds) {
        String slot = slotOf(kind, media);
        TreeMap<Integer, Product> byLength = timed.get(slot);
        Map.Entry<Integer, Product> fit = byLength == null ? null : byLength.ceilingEntry(Math.max(seconds, 0));
        if (fit != null) return Optional.of(fit.getValue());
        return Optional.ofNullable(untimed.get(slot));
    }

    /** The product's key for this kind, media and length, as {@code productKeys} answers it; empty = no product, so no key. */
    public List<String> keysFor(String kind, String media, int seconds) {
        return productFor(kind, media, seconds).map(p -> ProductKeys.exact(p.key())).orElse(List.of());
    }

    /** How many products the index holds (active, with a kind). */
    public int size() {
        int n = untimed.size();
        for (TreeMap<Integer, Product> t : timed.values()) n += t.size();
        return n;
    }

    private static String slotOf(String kind, String media) {
        return (kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT)) + "|" + (media == null ? "" : media.trim().toLowerCase(Locale.ROOT));
    }
}
