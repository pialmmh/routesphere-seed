package com.telcobright.seed.prepaid.api;

import com.telcobright.seed.sessionflow.spi.LedgerPort.Product;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §15 · the product a request consumes by Odoo's attributes: for a kind, the SMALLEST seconds not below the asked (a 25-second video →
 * the 30-second product); a kind with one untimed product (an image) answers it for any length; none fits → no key; an inactive product
 * is never answered; the index is rebuilt only when the catalog's map changes.
 */
class ProductIndexTest {

    private static Map<String, Product> products() {
        Map<String, Product> m = new LinkedHashMap<>();
        m.put("1002", new Product("1002", "V60", "Video ad 60 s", "video", 60, "view", true));
        m.put("1001", new Product("1001", "V30", "Video ad 30 s", "Video", 30, "view", true));
        m.put("1000", new Product("1000", "V10", "Video ad 10 s", "video", 10, "view", true));
        m.put("1003", new Product("1003", "IMG", "Image ad", "image", null, "view", true));
        m.put("1009", new Product("1009", "OLD", "Video ad 5 s (retired)", "video", 5, "view", false));
        return Map.copyOf(m);
    }

    @Test
    void theSmallestProductNotBelowTheAskedSeconds_theUntimedKindForAnyLength_noneFitsNoKey() {
        ProductIndex index = ProductIndex.of(products());
        assertThat(index.keysFor("video", 25)).as("a 25 s video consumes the 30 s product").containsExactly("1001");
        assertThat(index.keysFor("video", 30)).containsExactly("1001");
        assertThat(index.keysFor("video", 31)).containsExactly("1002");
        assertThat(index.keysFor("VIDEO", 3)).as("the retired 5 s product is never answered: the 10 s one is").containsExactly("1000");
        assertThat(index.keysFor("video", 61)).as("longer than every product: no key — cash where the rule allows").isEmpty();
        assertThat(index.keysFor("image", 0)).containsExactly("1003");
        assertThat(index.keysFor("image", 999)).containsExactly("1003");
        assertThat(index.keysFor("audio", 10)).isEmpty();
        assertThat(index.size()).isEqualTo(4);
    }

    @Test
    void over_keepsTheIndexWhileTheCatalogsMapIsTheSame_rebuildsOnANewMap() {
        Map<String, Product> map = products();
        ProductIndex first = ProductIndex.over(null, map);
        assertThat(ProductIndex.over(first, map)).isSameAs(first);
        assertThat(ProductIndex.over(first, products())).isNotSameAs(first);
        assertThat(ProductIndex.of(null).keysFor("video", 10)).isEmpty();
    }
}
