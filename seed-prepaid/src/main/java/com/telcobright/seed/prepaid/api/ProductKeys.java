package com.telcobright.seed.prepaid.api;

import java.util.ArrayList;
import java.util.List;

/**
 * §15 (the owner, 2026-10-29) · The PRODUCT KEYS a request may consume from a package, best first — what a lane's
 * {@code Prepaid.Builder.productKeys} answers. A package item's key is its {@code packageitem.prefix} ("map prefix = product id"):
 *
 * <ul>
 *   <li>the ad: ONE exact key — the Odoo product the view consumes (a 30-second video, an image), {@link #exact};</li>
 *   <li>the call and the SMS: the called number's prefixes, longest first ("880" for Bangladesh minutes, "8801" for its mobiles) —
 *       {@link #prefixesOf}: at most {@value #LONGEST} map gets, never a scan of a list.</li>
 * </ul>
 *
 * Every key is trimmed; the catalog's items are keyed the same way, so a key hits by equality.
 */
public final class ProductKeys {

    /** The longest prefix tried (a number has at most 15 digits, E.164). */
    public static final int LONGEST = 15;

    private ProductKeys() {}

    /** One exact key (the ad's product); a blank key names nothing. */
    public static List<String> exact(String key) {
        String k = key == null ? "" : key.trim();
        return k.isEmpty() ? List.of() : List.of(k);
    }

    /** The number's prefixes, longest first — "8801700" → 8801700, 880170, 88017, 8801, 880, 88, 8 — over its digits only; a blank number names nothing. */
    public static List<String> prefixesOf(String number) {
        String digits = number == null ? "" : number.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return List.of();
        int longest = Math.min(digits.length(), LONGEST);
        List<String> keys = new ArrayList<>(longest);
        for (int n = longest; n >= 1; n--) keys.add(digits.substring(0, n));
        return List.copyOf(keys);
    }

    /** No key: the request consumes no package item (cash only, where the rule allows). */
    public static List<String> none() { return List.of(); }
}
