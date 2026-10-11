package com.telcobright.seed.sessionflow.testkit;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The testkit's ledger in the SWITCH ledger's shape ({@link InMemoryLedger#accountKeyed()}): a tier pays from the account its rating
 * step named, and a tier that names none is REFUSED — where the default (the ledger that picks the account itself, road 16) books on
 * the partner. The wifi architect's find, 2026-10-11: a two-tier "rate plans on" story passed on the default stand-in although the
 * real switch ledger refuses the tier above the leaf with {@code NO_ACCOUNT}. A stand-in must not hide a refusal.
 */
class InMemoryLedgerAccountKeyedTest {

    private static LevelAdmission tier(int index, String db, int partnerId, Long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(partnerId);
        partner.setPartnerName("partner-" + partnerId);
        PackageAccount account = null;
        if (accountId != null) {
            account = new PackageAccount();
            account.setId(accountId);
        }
        LevelAdmission level = new LevelAdmission(index, new Tenant(db), partner, account);
        level.setDebitReference("s-1#L" + index);
        return level;
    }

    @Test
    void aTierThatNamesNoAccount_isRefusedNO_ACCOUNT_andThePartnersMoneyNeverMoves() {
        InMemoryLedger switchLedger = InMemoryLedger.accountKeyed().fund("btcl", 44, "100.00");

        assertThatThrownBy(() -> switchLedger.reserve(tier(1, "btcl", 44, null), new BigDecimal("0.40"), "s-1#L1"))
            .isInstanceOf(LedgerRefusal.class).hasMessageContaining("names no package account")
            .extracting(e -> ((LedgerRefusal) e).code()).isEqualTo(InMemoryLedger.NO_ACCOUNT);

        assertThat(switchLedger.balanceOf("btcl", 44)).as("a partner's balance is not the switch ledger's to take").isEqualByComparingTo("100.00");
        assertThat(switchLedger.openReserves()).isZero();
    }

    /** The control: the SAME tier on the default ledger is booked on the partner — the shape that hid the refusal. */
    @Test
    void theDefaultLedger_booksTheSameTierOnThePartner() {
        InMemoryLedger road16 = new InMemoryLedger().fund("btcl", 44, "100.00");

        assertThat(road16.reserve(tier(1, "btcl", 44, null), new BigDecimal("0.40"), "s-1#L1")).isPresent();

        assertThat(road16.balanceOf("btcl", 44)).isEqualByComparingTo("99.60");
    }

    @Test
    void anAccountTheLedgerDoesNotHold_isRefusedACCOUNT_NOT_FOUND() {
        InMemoryLedger switchLedger = InMemoryLedger.accountKeyed().fundAccount("res_45", 5101L, "30", "TF_min");

        assertThatThrownBy(() -> switchLedger.reserve(tier(0, "res_45", 100172, 9999L), BigDecimal.ONE, "s-1#L0"))
            .isInstanceOf(LedgerRefusal.class).hasMessageContaining("account 9999").hasMessageContaining("not held in res_45")
            .extracting(e -> ((LedgerRefusal) e).code()).isEqualTo(InMemoryLedger.ACCOUNT_NOT_FOUND);
        assertThatThrownBy(() -> switchLedger.reserve(tier(0, "btcl", 100172, 5101L), BigDecimal.ONE, "s-1#L0"))
            .as("an account is held in ONE tier: the same id in another tier is another account")
            .isInstanceOf(LedgerRefusal.class).extracting(e -> ((LedgerRefusal) e).code()).isEqualTo(InMemoryLedger.ACCOUNT_NOT_FOUND);
    }

    @Test
    void theNamedAccountPays_inItsOwnUnit_andSettlesBackToItself() {
        InMemoryLedger switchLedger = InMemoryLedger.accountKeyed()
            .fundAccount("res_45", 5101L, "30", "TF_min")
            .fund("res_45", 100172, "500.00");
        LevelAdmission leaf = tier(0, "res_45", 100172, 5101L);

        Reservation held = switchLedger.reserve(leaf, BigDecimal.ONE, "s-1#L0").orElseThrow();
        TierSettlement settled = switchLedger.settle(leaf, new BigDecimal("0.5"));

        assertThat(held.account()).isEqualTo(5101L);
        assertThat(held.uom()).as("the unit is the ACCOUNT's").isEqualTo("TF_min");
        assertThat(held.balanceBefore()).isEqualByComparingTo("30");
        assertThat(held.balanceAfter()).isEqualByComparingTo("29");
        assertThat(settled.returned()).isEqualByComparingTo("0.5");
        assertThat(switchLedger.balanceOfAccount("res_45", 5101L)).isEqualByComparingTo("29.5");
        assertThat(switchLedger.balanceOf("res_45", 100172)).as("the partner's own balance is never touched").isEqualByComparingTo("500.00");
        assertThat(switchLedger.openReserves()).isZero();
    }

    @Test
    void aBalanceShortOfTheAmount_isEmpty_notARefusal_andAReleaseGivesEverythingBack() {
        InMemoryLedger switchLedger = InMemoryLedger.accountKeyed().fundAccount("res_45", 5101L, "2", "TF_min");
        LevelAdmission leaf = tier(0, "res_45", 100172, 5101L);

        assertThat(switchLedger.reserve(leaf, new BigDecimal("3"), "s-2#L0")).as("no bucket left is 'cannot pay', never a refusal").isEmpty();
        switchLedger.reserve(leaf, new BigDecimal("2"), "s-1#L0").orElseThrow();
        Reservation again = switchLedger.reserve(leaf, new BigDecimal("2"), "s-1#L0").orElseThrow();
        switchLedger.release(leaf, "the session never started");

        assertThat(again.repeated()).as("a repeated reference answers the first and moves nothing").isTrue();
        assertThat(switchLedger.balanceOfAccount("res_45", 5101L)).isEqualByComparingTo("2");
        assertThat(switchLedger.openReserves()).isZero();
    }
}
