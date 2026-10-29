package com.telcobright.seed.prepaid.api;

import com.telcobright.seed.sessionflow.spi.LedgerPort.Product;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §15 · the product a request consumes by Odoo's attributes: for a kind and a media, the SMALLEST seconds not below the asked (a
 * 25-second video → the 30-second product); an untimed product (an image) for any length; none fits → no key; an inactive product is
 * never answered; the index is rebuilt only when the catalog's map changes.
 */
class ProductIndexTest {

    private static Product ad(String key, String media, Integer seconds, boolean active) {
        return new Product(key, Long.valueOf(key), "C" + key, "Ad " + key, "ad", media, seconds, "view", active, "odoo", 30);
    }

    private static Map<String, Product> products() {
        Map<String, Product> m = new LinkedHashMap<>();
        m.put("1002", ad("1002", "video", 60, true));
        m.put("1001", ad("1001", "Video", 30, true));
        m.put("1000", ad("1000", "video", 10, true));
        m.put("1003", ad("1003", "image", null, true));
        m.put("1009", ad("1009", "video", 5, false));
        m.put("3001", new Product("3001", null, "WIFI-1HR", "WiFi one hour", "wifi-internet", null, 3600, "TF_min", true, "portal", 101));
        return Map.copyOf(m);
    }

    @Test
    void theSmallestProductNotBelowTheAskedSeconds_theUntimedMediaForAnyLength_noneFitsNoKey() {
        ProductIndex index = ProductIndex.of(products());
        assertThat(index.keysFor("ad", "video", 25)).as("a 25 s video consumes the 30 s product").containsExactly("1001");
        assertThat(index.keysFor("ad", "video", 30)).containsExactly("1001");
        assertThat(index.keysFor("ad", "video", 31)).containsExactly("1002");
        assertThat(index.keysFor("AD", "VIDEO", 3)).as("the retired 5 s product is never answered: the 10 s one is").containsExactly("1000");
        assertThat(index.keysFor("ad", "video", 61)).as("longer than every product: no key — cash where the rule allows").isEmpty();
        assertThat(index.keysFor("ad", "image", 0)).containsExactly("1003");
        assertThat(index.keysFor("ad", "image", 999)).containsExactly("1003");
        assertThat(index.keysFor("ad", "audio", 10)).isEmpty();
        assertThat(index.keysFor("wifi-internet", null, 1800)).as("a kind without media: its own slot").containsExactly("3001");
        assertThat(index.size()).isEqualTo(5);
    }

    @Test
    void over_keepsTheIndexWhileTheCatalogsMapIsTheSame_rebuildsOnANewMap() {
        Map<String, Product> map = products();
        ProductIndex first = ProductIndex.over(null, map);
        assertThat(ProductIndex.over(first, map)).isSameAs(first);
        assertThat(ProductIndex.over(first, products())).isNotSameAs(first);
        assertThat(ProductIndex.of(null).keysFor("ad", "video", 10)).isEmpty();
    }
}
