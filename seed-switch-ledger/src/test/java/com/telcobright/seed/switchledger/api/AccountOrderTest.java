package com.telcobright.seed.switchledger.api;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.DynamicContext;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0077-A item 2 — the account order of the call switch, as pure functions over the tree ({@code ReserveBalanceStep.orderedAccounts},
 * {@code unexpired}, {@code bdtAccounts}, moved with citation). Partner 701 of res_44 holds, in the tree's order: an EXPIRED minute bucket,
 * cash (BDT), an SMS bucket (OTH_ea), a unit nobody rates (GB), an EXPIRED cash account, a live minute bucket (TF_min) with no expiry known,
 * and a second live cash account.
 */
class AccountOrderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);

    private final Tenant root = new TenantTreeBuilder()
        .root("res_44")
            .partner(701, "Unilever", PartnerType.CUSTOMER)
            .partner(702, "Poorco", PartnerType.CUSTOMER)
            .account(701, 11, 1, "TF_min", "30", NOW.minusDays(1))     // expired yesterday
            .account(701, 12, 2, "BDT", "100", NOW.plusDays(30))
            .account(701, 13, 3, "OTH_ea", "500", NOW.plusDays(30))
            .account(701, 14, 4, "GB", "7", NOW.plusDays(30))
            .account(701, 15, 5, "BDT", "50", NOW)                      // expires exactly now: expired
            .account(701, 16, 6, "TF_min", "60", null)                  // no expiry known: never expires
            .account(701, 17, 7, "BDT", "20", NOW.plusMinutes(1))
            .and()
        .build();
    private final DynamicContext tree = root.getContext();
    private final Partner unilever = tree.getPartners().get(701);
    private final Partner poorco = tree.getPartners().get(702);

    private static List<Long> ids(List<PackageAccount> accounts) { return accounts.stream().map(PackageAccount::getId).toList(); }

    @Test
    void fundable_isUnexpiredOnly_bucketsFirst_thenCash_thenTheRest() {
        assertThat(ids(AccountOrder.fundable(unilever, tree, NOW)))
            .as("TF_min and OTH_ea first in the tree's order, then BDT in the tree's order, then the unit nobody rates; 11 and 15 expired")
            .containsExactly(13L, 16L, 12L, 17L, 14L);
    }

    @Test
    void buckets_areTheUnitBucketsOnly_noCash_theOwner_noPackageNoWifi() {
        assertThat(ids(AccountOrder.buckets(unilever, tree, NOW))).containsExactly(13L, 16L);
    }

    @Test
    void cashOnly_isBdtOnly_theCallsInternationalRule() {
        assertThat(ids(AccountOrder.cashOnly(unilever, tree, NOW))).containsExactly(12L, 17L);
    }

    @Test
    void aPartnerWithNoAccounts_andATreeWithNoMap_answerEmpty() {
        assertThat(AccountOrder.fundable(poorco, tree, NOW)).isEmpty();
        assertThat(AccountOrder.buckets(poorco, tree, NOW)).isEmpty();
        assertThat(AccountOrder.cashOnly(poorco, tree, NOW)).isEmpty();
        assertThat(AccountOrder.fundable(unilever, null, NOW)).isEmpty();
    }

    @Test
    void theAnswerIsACopy_theTreeIsNeverTouched() {
        List<PackageAccount> before = List.copyOf(tree.getPartnerIdWisePackageAccounts().get(701L));
        AccountOrder.fundable(unilever, tree, NOW);
        assertThat(tree.getPartnerIdWisePackageAccounts().get(701L)).containsExactlyElementsOf(before);
    }
}
