package com.telcobright.seed.prepaid.api;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L3 · The library's ONE package rule ({@link PackageMatch}), as pure functions: the lane's units only, active only, in the CATALOG's
 * order (prime-context's: the operator's priority, then the soonest expiry — S6: never re-sorted), then cash; the three rules.
 */
class PackageMatchTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 12, 0);

    @Test
    void theLanesUnitsOnly_activeOnly_inTheCatalogsOrder_thenCash() {
        List<PackageAccount> catalog = List.of(                                               // as prime-context serves it: the soonest first
            account(3, "AD_view", NOW.plusMonths(2)), account(1, "AD_view", NOW.plusYears(3)), account(2, "BDT", NOW.plusYears(3)),
            account(4, "AD_sec", NOW.plusYears(1)), account(5, "AD_view", NOW.minusDays(1)), account(6, "TF_min", NOW.plusYears(1)));

        assertThat(ids(PackageMatch.payable(catalog, Set.of("AD_view"), AccountRule.UNITS_THEN_CASH, NOW)))
            .as("the soonest view package, the later one, then cash — never seconds, minutes, or an expired package").containsExactly(3L, 1L, 2L);
        assertThat(ids(PackageMatch.payable(catalog, Set.of("AD_sec"), AccountRule.UNITS_THEN_CASH, NOW))).containsExactly(4L, 2L);
    }

    /** S6 · An operator's own priority (prime-context puts that purchase first) is never undone by a sooner expiry. */
    @Test
    void theOperatorsPriority_isNeverUndone_byASoonerExpiry() {
        List<PackageAccount> catalog = List.of(account(1, "AD_view", NOW.plusYears(3)), account(3, "AD_view", NOW.plusMonths(2)));

        assertThat(ids(PackageMatch.payable(catalog, Set.of("AD_view"), AccountRule.UNITS_ONLY, NOW))).containsExactly(1L, 3L);
    }

    @Test
    void noPackageMatches_cashAlonePays_atTheLanesRatePlanPrice() {
        List<PackageAccount> catalog = List.of(account(1, "TF_min", NOW.plusYears(1)), account(2, "BDT", null));

        assertThat(ids(PackageMatch.payable(catalog, Set.of("AD_view"), AccountRule.UNITS_THEN_CASH, NOW))).containsExactly(2L);
    }

    @Test
    void aTieOrAnUnknownExpiry_keepsTheCatalogsOrder() {
        LocalDateTime same = NOW.plusMonths(6);
        List<PackageAccount> catalog = List.of(account(1, "AD_view", null), account(2, "AD_view", same), account(3, "AD_view", same));

        assertThat(ids(PackageMatch.payable(catalog, Set.of("AD_view"), AccountRule.UNITS_ONLY, NOW))).containsExactly(1L, 2L, 3L);
    }

    @Test
    void theThreeRules() {
        List<PackageAccount> catalog = List.of(account(1, "OTH_ea", NOW.plusYears(1)), account(2, "BDT", NOW.plusYears(1)));

        assertThat(ids(PackageMatch.payable(catalog, PackageMatch.CALL_AND_SMS_UNITS, AccountRule.UNITS_THEN_CASH, NOW))).containsExactly(1L, 2L);
        assertThat(ids(PackageMatch.payable(catalog, PackageMatch.CALL_AND_SMS_UNITS, AccountRule.UNITS_ONLY, NOW))).containsExactly(1L);
        assertThat(ids(PackageMatch.payable(catalog, PackageMatch.CALL_AND_SMS_UNITS, AccountRule.CASH_ONLY, NOW))).containsExactly(2L);
        assertThat(PackageMatch.payable(null, Set.of("AD_view"), AccountRule.UNITS_THEN_CASH, NOW)).isEmpty();
    }

    private static PackageAccount account(long id, String uom, LocalDateTime expires) {
        PackageAccount a = new PackageAccount();
        a.setId(id);
        a.setUom(uom);
        a.setExpireDate(expires);
        return a;
    }

    private static List<Long> ids(List<PackageAccount> accounts) { return accounts.stream().map(PackageAccount::getId).toList(); }
}
