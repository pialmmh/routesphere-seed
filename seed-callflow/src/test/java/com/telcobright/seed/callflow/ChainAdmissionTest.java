package com.telcobright.seed.callflow;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.AdAdmission;
import com.telcobright.seed.callflow.api.AdCallPayload;
import com.telcobright.seed.callflow.internal.ChainAdmission;
import com.telcobright.seed.callflow.spi.TenantLookup;
import com.telcobright.seed.callflow.testkit.FakeBillingPort;
import com.telcobright.seed.callflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.pipeline.StepMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chain admission of design §2.3 on the story's tree (§5): tenant btcl with the reseller R1 (= tenant res_44) and
 * Unilever as R1's client; rule 1001. Every road proven by a mutation on the fake ledger.
 */
class ChainAdmissionTest {

    static final int UNILEVER = 701, WALTON = 702, R1 = 44, NETWORK = 9, HOUSE = 1;

    Tenant btcl;
    FakeBillingPort ledger;
    ChainAdmission chain;

    @BeforeEach
    void tree() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl")
            .partner(HOUSE, "BTCL", PartnerType.CUSTOMER)
            .partner(NETWORK, "BTCL network", PartnerType.CUSTOMER)
            .partner(R1, "res_44", PartnerType.RESELLER)
            .partner(WALTON, "Walton", PartnerType.CUSTOMER, 1)
            .plan(20, "operator plan for R1", R1, 2).perView(20, "1001", "any", "0.40").perView(20, "100", "any", "0.10")
            .plan(21, "operator plan for Walton", WALTON, 2).perView(21, "1001", "any", "0.45").perView(21, "1001", "video", "0.90")
            .and()
        .tenant("res_44", "btcl")
            .partner(UNILEVER, "Unilever", PartnerType.CUSTOMER, 5)
            .plan(10, "R1's plan for Unilever", UNILEVER, 2).perView(10, "1001", "any", "0.50").perSecond(10, "100", "video", "0.02");
        btcl = b.build();
        ledger = new FakeBillingPort().balance(UNILEVER, "10.00").balance(R1, "100.00").balance(WALTON, "3.00");
        chain = new ChainAdmission(TenantLookup.of(btcl), ledger, Clock.systemUTC());
    }

    static AdCallPayload view(int payer, String called, String media, int seconds, boolean fallback) {
        return AdCallPayload.minimal("ad-1", "btcl", "wifi", "8801711111111", called, System.currentTimeMillis())
            .withCandidate(payer, 12, "lux-soap", 5, "lux-soap", "lux-1", payer, media, "lux-1.mp4", seconds, 1, fallback);
    }

    @Test
    void unilever_is_debited_at_two_tiers_leaf_first_on_each_tiers_own_plan() {
        AdAdmission a = chain.admit(view(UNILEVER, "1001", "image", 15, false), "req-1", StepMode.LIVE);
        assertThat(a.admitted()).as(a.cause()).isTrue();
        assertThat(a.entryTenant().getDbName()).isEqualTo("res_44");
        assertThat(a.entryPartner().getIdPartner()).isEqualTo(UNILEVER);
        List<LevelAdmission> levels = a.levels();
        assertThat(levels).hasSize(2);
        assertThat(levels.get(0).getDbName()).isEqualTo("res_44");
        assertThat(levels.get(0).getPartnerId()).isEqualTo(UNILEVER);
        assertThat(levels.get(0).getRate()).isEqualByComparingTo("0.50");
        assertThat(levels.get(0).getReservedAmount()).isEqualByComparingTo("0.50");
        assertThat(levels.get(0).getDebitReference()).isEqualTo("req-1#L0");
        assertThat(levels.get(0).getRatePrefix()).isEqualTo("1001");
        assertThat(levels.get(0).getBalanceBefore()).isEqualByComparingTo("10.00");
        assertThat(levels.get(0).getBalanceAfter()).isEqualByComparingTo("9.50");
        assertThat(levels.get(0).getPackageAccountId()).as("the ledger's account rides where the switch's PackageAccount would").isNotNull();
        assertThat(levels.get(1).getDbName()).isEqualTo("btcl");
        assertThat(levels.get(1).getPartnerId()).as("the reseller row in the parent, by name").isEqualTo(R1);
        assertThat(levels.get(1).getRate()).isEqualByComparingTo("0.40");
        assertThat(levels.get(1).getDebitReference()).isEqualTo("req-1#L1");
        assertThat(ledger.debits).extracting(FakeBillingPort.Debit::reference).containsExactly("req-1#L0", "req-1#L1");
        assertThat(ledger.balanceOf(UNILEVER)).isEqualByComparingTo("9.50");
        assertThat(ledger.balanceOf(R1)).isEqualByComparingTo("99.60");
        assertThat(ledger.credits).isEmpty();
    }

    @Test
    void a_later_tiers_refusal_credits_the_earlier_tier_back_and_nothing_is_admitted() {
        ledger.balance(R1, "0.10");
        AdAdmission a = chain.admit(view(UNILEVER, "1001", "image", 15, false), "req-2", StepMode.LIVE);
        assertThat(a.admitted()).isFalse();
        assertThat(a.cause()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(a.levels()).isEmpty();
        assertThat(ledger.debits).hasSize(2);
        assertThat(ledger.credits).hasSize(1);
        FakeBillingPort.Credit back = ledger.credits.get(0);
        assertThat(back.partnerId()).isEqualTo(UNILEVER);
        assertThat(back.amount()).isEqualByComparingTo("0.50");
        assertThat(back.reference()).isEqualTo("req-2#L0#C");
        assertThat(back.reason()).isEqualTo("compensation:INSUFFICIENT_BALANCE");
        assertThat(ledger.balanceOf(UNILEVER)).as("the leaf's debit is back").isEqualByComparingTo("10.00");
        assertThat(ledger.balanceOf(R1)).isEqualByComparingTo("0.10");
        assertThat(chain.activeOf(UNILEVER)).as("the slot taken at the entry tier is released on the refusal").isZero();
    }

    @Test
    void a_system_fault_stops_the_chain_reports_billing_system_error_and_credits_what_was_taken() {
        ledger.faultingPartner = R1;
        AdAdmission a = chain.admit(view(UNILEVER, "1001", "image", 15, false), "req-3", StepMode.LIVE);
        assertThat(a.admitted()).isFalse();
        assertThat(a.cause()).isEqualTo("BILLING_SYSTEM_ERROR");
        assertThat(a.systemFault()).isTrue();
        assertThat(ledger.debits).as("the leaf was debited, the root threw — no third attempt").hasSize(1);
        assertThat(ledger.credits).hasSize(1);
        assertThat(ledger.credits.get(0).reason()).isEqualTo("compensation:BILLING_SYSTEM_ERROR");
        assertThat(ledger.balanceOf(UNILEVER)).isEqualByComparingTo("10.00");
    }

    @Test
    void a_fault_at_the_first_tier_debits_nothing_at_all() {
        ledger.faulting = true;
        AdAdmission a = chain.admit(view(UNILEVER, "1001", "image", 15, false), "req-3b", StepMode.LIVE);
        assertThat(a.cause()).isEqualTo("BILLING_SYSTEM_ERROR");
        assertThat(ledger.debits).isEmpty();
        assertThat(ledger.balanceOf(UNILEVER)).isEqualByComparingTo("10.00");
    }

    @Test
    void simulate_rates_every_tier_and_debits_none() {
        AdAdmission a = chain.admit(view(UNILEVER, "1001", "image", 15, false), "req-4", StepMode.SIMULATE);
        assertThat(a.admitted()).isTrue();
        assertThat(a.levels()).hasSize(2);
        assertThat(a.levels().get(0).getRate()).isEqualByComparingTo("0.50");
        assertThat(a.levels().get(1).getRate()).isEqualByComparingTo("0.40");
        assertThat(a.levels().get(0).getDebitReference()).isNull();
        assertThat(ledger.debits).isEmpty();
        assertThat(chain.activeOf(UNILEVER)).as("simulate takes no slot").isZero();
    }

    @Test
    void the_operators_direct_client_is_one_tier_and_the_media_row_beats_the_any_row() {
        AdAdmission a = chain.admit(view(WALTON, "1001", "video", 15, false), "req-5", StepMode.LIVE);
        assertThat(a.admitted()).as(a.cause()).isTrue();
        assertThat(a.levels()).hasSize(1);
        assertThat(a.levels().get(0).getDbName()).isEqualTo("btcl");
        assertThat(a.levels().get(0).getRate()).as("the video row, not the any row").isEqualByComparingTo("0.90");
        assertThat(ledger.balanceOf(WALTON)).isEqualByComparingTo("2.10");
        AdAdmission image = chain.admit(view(WALTON, "1001", "image", 15, false), "req-5b", StepMode.SIMULATE);
        assertThat(image.levels().get(0).getRate()).isEqualByComparingTo("0.45");
    }

    @Test
    void a_per_second_row_charges_the_required_seconds_and_the_longest_prefix_wins() {
        AdAdmission a = chain.admit(view(UNILEVER, "1001", "video", 15, false), "req-6", StepMode.SIMULATE);
        assertThat(a.levels().get(0).getRate()).as("prefix 1001 (per view 0.50) beats prefix 100 (per second)").isEqualByComparingTo("0.50");
        AdAdmission b = chain.admit(view(UNILEVER, "1002", "video", 15, false), "req-6b", StepMode.SIMULATE);
        assertThat(b.admitted()).as(b.cause()).isTrue();
        assertThat(b.levels().get(0).getRate()).as("prefix 100 per second × 15 s").isEqualByComparingTo("0.30");
        assertThat(b.levels().get(0).getRatePrefix()).isEqualTo("100");
        assertThat(b.levels().get(1).getRate()).as("the operator's tier on ITS plan: prefix 100 per view").isEqualByComparingTo("0.10");
    }

    @Test
    void an_unrated_view_refuses_with_unrated_before_any_debit() {
        AdAdmission a = chain.admit(view(UNILEVER, "9999", "image", 15, false), "req-7", StepMode.LIVE);
        assertThat(a.admitted()).isFalse();
        assertThat(a.cause()).isEqualTo(ChainAdmission.UNRATED);
        assertThat(ledger.debits).isEmpty();
    }

    @Test
    void unknown_and_deactivated_partners_are_refused_by_name() {
        assertThat(chain.admit(view(4242, "1001", "image", 15, false), "req-8", StepMode.LIVE).cause()).isEqualTo("PARTNER_NOT_FOUND");
        btcl.findTenantByDbName("res_44").getContext().getPartners().get(UNILEVER).setStatus("DEACTIVATED");
        AdAdmission a = chain.admit(view(UNILEVER, "1001", "image", 15, false), "req-9", StepMode.LIVE);
        assertThat(a.cause()).isEqualTo("PARTNER_DEACTIVATED");
        assertThat(a.entryTenant().getDbName()).as("the failed CDR row knows the entry tenant").isEqualTo("res_44");
        assertThat(ledger.debits).isEmpty();
    }

    @Test
    void the_advertisers_concurrent_cap_holds_until_the_session_is_released() {
        AdAdmission first = chain.admit(view(WALTON, "1001", "image", 15, false), "req-10", StepMode.LIVE);
        assertThat(first.admitted()).isTrue();
        AdAdmission second = chain.admit(view(WALTON, "1001", "image", 15, false), "req-11", StepMode.LIVE);
        assertThat(second.cause()).as("Walton's cap is 1 live view").isEqualTo("CHANNEL_LIMIT_REACHED");
        assertThat(ledger.debits).as("no debit behind a refused cap").hasSize(1);
        chain.release(first);
        assertThat(chain.admit(view(WALTON, "1001", "image", 15, false), "req-12", StepMode.LIVE).admitted()).isTrue();
    }

    @Test
    void the_fallback_walks_the_tiers_free_and_debits_nothing() {
        AdAdmission a = chain.admit(view(HOUSE, "1001", "image", 15, true), "req-13", StepMode.LIVE);
        assertThat(a.admitted()).isTrue();
        assertThat(a.levels()).hasSize(1);
        assertThat(a.levels().get(0).getRate()).isEqualByComparingTo("0");
        assertThat(a.levels().get(0).getDebitReference()).isNull();
        assertThat(ledger.debits).isEmpty();
        AdAdmission noPartner = chain.admit(view(0, "1001", "image", 15, true), "req-14", StepMode.LIVE);
        assertThat(noPartner.admitted()).as("a house ad of a tenant with no partner row of its own").isTrue();
        assertThat(noPartner.levels()).isEmpty();
        assertThat(noPartner.entryTenant().getDbName()).isEqualTo("btcl");
    }

    @Test
    void compensate_credits_every_debited_tier_of_an_admitted_candidate() {
        AdAdmission a = chain.admit(view(UNILEVER, "1001", "image", 15, false), "req-15", StepMode.LIVE);
        chain.compensate(a, "req-15", "quota-gone");
        assertThat(ledger.credits).extracting(FakeBillingPort.Credit::reference).containsExactly("req-15#L0#C", "req-15#L1#C");
        assertThat(ledger.credits).allSatisfy(c -> assertThat(c.reason()).isEqualTo("compensation:quota-gone"));
        assertThat(ledger.balanceOf(UNILEVER)).isEqualByComparingTo("10.00");
        assertThat(ledger.balanceOf(R1)).isEqualByComparingTo("100.00");
    }

    @Test
    void a_replayed_reference_never_debits_twice() {
        AdAdmission a = chain.admit(view(WALTON, "1001", "image", 15, false), "req-16", StepMode.LIVE);
        chain.release(a);
        chain.admit(view(WALTON, "1001", "image", 15, false), "req-16", StepMode.LIVE);
        assertThat(ledger.balanceOf(WALTON)).as("the same reference answers the first debit").isEqualByComparingTo(new BigDecimal("2.55"));
    }
}
