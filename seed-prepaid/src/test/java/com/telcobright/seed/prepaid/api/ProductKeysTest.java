package com.telcobright.seed.prepaid.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** §15 · the keys a request may consume, best first: one exact key (the ad), the called number's prefixes longest first (the call, the SMS). */
class ProductKeysTest {

    @Test
    void exact_oneTrimmedKey_blankNamesNothing() {
        assertThat(ProductKeys.exact(" 1001 ")).containsExactly("1001");
        assertThat(ProductKeys.exact("  ")).isEmpty();
        assertThat(ProductKeys.exact(null)).isEmpty();
    }

    @Test
    void prefixesOf_longestFirst_digitsOnly_atMostFifteen() {
        assertThat(ProductKeys.prefixesOf("+880 17")).containsExactly("88017", "8801", "880", "88", "8");
        assertThat(ProductKeys.prefixesOf("12345678901234567890")).hasSize(ProductKeys.LONGEST).first().isEqualTo("123456789012345");
        assertThat(ProductKeys.prefixesOf("abc")).isEmpty();
        assertThat(ProductKeys.none()).isEmpty();
    }
}
