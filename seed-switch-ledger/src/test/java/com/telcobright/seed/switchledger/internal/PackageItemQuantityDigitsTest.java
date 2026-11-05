package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.switchledger.internal.LedgerEntities.Digits;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3.77 (the owner 2026-11-05: "house ad credit can be a very large number to simulate unlimited credit"): a package item's quantity takes
 * 18 digits, 2 after the point — the column prime-context's changelog 019 widens. The ad console sells minutes as seconds (minutes × 60):
 * 100,000,000 minutes = 6,000,000,000 seconds, refused before at 10 digits, must fit now.
 */
class PackageItemQuantityDigitsTest {

    @Test
    void a_package_items_quantity_takes_18_digits_2_after_the_point() {
        Digits quantity = LedgerEntities.known().stream().filter(k -> k.table().equals("packageitem")).findFirst().orElseThrow()
            .shape().digits().get("quantity");
        assertThat(quantity).as("packageitem.quantity").isEqualTo(new Digits(18, 2));
        assertThat(RowRules.fits(new BigDecimal("6000000000"), quantity)).as("100,000,000 minutes in seconds (refused at 10 digits)").isTrue();
        assertThat(RowRules.fits(new BigDecimal("9999999999999999.99"), quantity)).as("the column's largest").isTrue();
        assertThat(RowRules.fits(new BigDecimal("10000000000000000"), quantity)).as("17 digits before the point").isFalse();
        assertThat(RowRules.fits(new BigDecimal("1.005"), quantity)).as("a third decimal").isFalse();
    }
}
