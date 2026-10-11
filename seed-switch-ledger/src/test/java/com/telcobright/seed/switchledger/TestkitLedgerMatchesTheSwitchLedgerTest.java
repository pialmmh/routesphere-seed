package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The base's testkit ledger in the switch ledger's shape ({@code InMemoryLedger.accountKeyed()}) answers the SAME questions as the
 * real switch ledger (a {@link MemLedgerPort} over a real MemLedger) with the SAME answers — the two refusals word for word in their
 * code, a paid window and its settlement to the same numbers. A flow's stories run on the stand-in; this is what makes them true of
 * the real one. The stand-in once booked a tier without an account on its partner and hid the real refusal (the wifi architect,
 * 2026-10-11): the two ledgers are held together here so that cannot return.
 */
class TestkitLedgerMatchesTheSwitchLedgerTest {

    private final LedgerLab lab = LedgerLab.open().schema("res_2").account("res_2", 11, 1, "OTH_ea", "100");
    private LedgerPort real;
    private final InMemoryLedger standIn = InMemoryLedger.accountKeyed().fundAccount("res_2", 11L, "100", "OTH_ea").fund("res_2", 701, "999");

    @BeforeEach
    void start() {
        lab.start();
        real = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC()).port();
    }

    @AfterEach
    void stop() { lab.close(); }

    private static LevelAdmission tier(Long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(701);
        partner.setPartnerName("Unilever");
        PackageAccount account = null;
        if (accountId != null) {
            account = new PackageAccount();
            account.setId(accountId);
            account.setUom("OTH_ea");
        }
        LevelAdmission level = new LevelAdmission(0, new Tenant("res_2"), partner, account);
        level.setRate(new BigDecimal("10"));
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    /** The base records a reserve on its level ({@code AdmissionChain.recordReserve}); a direct test does the same by hand. */
    private static void record(LevelAdmission level, Reservation held, String reference) {
        level.setDebitReference(reference);
        level.setReservedAmount(held.reserved());
        level.setBalanceAfter(held.balanceAfter());
        level.incrementReservationCount();
    }

    private static String refusalOf(LedgerPort ledger, Long accountId, String reference) {
        Throwable t = catchThrowable(() -> ledger.reserve(tier(accountId), new BigDecimal("10"), reference));
        assertThat(t).as("a refusal, never an answer and never a fault").isInstanceOf(LedgerRefusal.class);
        return ((LedgerRefusal) t).code();
    }

    @Test
    void theTwoRefusalWordsAreTheSame() {
        assertThat(InMemoryLedger.NO_ACCOUNT).isEqualTo(MemLedgerPort.NO_ACCOUNT);
        assertThat(InMemoryLedger.ACCOUNT_NOT_FOUND).isEqualTo(MemLedgerPort.ACCOUNT_NOT_FOUND);
    }

    @Test
    void aTierThatNamesNoAccount_isRefusedByBoth_inTheSameWord() {
        assertThat(refusalOf(standIn, null, "t-1#L0")).isEqualTo(refusalOf(real, null, "t-1#L0")).isEqualTo(MemLedgerPort.NO_ACCOUNT);
    }

    @Test
    void anAccountNotHeld_isRefusedByBoth_inTheSameWord() {
        assertThat(refusalOf(standIn, 99L, "t-2#L0")).isEqualTo(refusalOf(real, 99L, "t-2#L0")).isEqualTo(MemLedgerPort.ACCOUNT_NOT_FOUND);
    }

    @Test
    void aWindowHeldAndSettled_comesToTheSameNumbersOnBoth() {
        LevelAdmission onReal = tier(11L);
        LevelAdmission onStandIn = tier(11L);

        Reservation heldReal = real.reserve(onReal, new BigDecimal("10"), "t-3#L0").orElseThrow();
        Reservation heldStandIn = standIn.reserve(onStandIn, new BigDecimal("10"), "t-3#L0").orElseThrow();
        record(onReal, heldReal, "t-3#L0");
        record(onStandIn, heldStandIn, "t-3#L0");
        TierSettlement settledReal = real.settle(onReal, new BigDecimal("4"));
        TierSettlement settledStandIn = standIn.settle(onStandIn, new BigDecimal("4"));

        assertThat(heldStandIn.account()).isEqualTo(heldReal.account()).isEqualTo(11L);
        assertThat(heldStandIn.uom()).isEqualTo(heldReal.uom()).isEqualTo("OTH_ea");
        assertThat(heldStandIn.balanceBefore()).isEqualByComparingTo(heldReal.balanceBefore()).isEqualByComparingTo("100");
        assertThat(heldStandIn.balanceAfter()).isEqualByComparingTo(heldReal.balanceAfter()).isEqualByComparingTo("90");
        assertThat(settledStandIn.charged()).isEqualByComparingTo(settledReal.charged()).isEqualByComparingTo("4");
        assertThat(settledStandIn.returned()).isEqualByComparingTo(settledReal.returned()).isEqualByComparingTo("6");
        assertThat(settledStandIn.balanceAfter()).isEqualByComparingTo(settledReal.balanceAfter()).isEqualByComparingTo("96");
        assertThat(standIn.balanceOf("res_2", 701)).as("the stand-in never touched the partner's own balance").isEqualByComparingTo("999");
    }

    @Test
    void aBalanceShort_isEmptyOnBoth_neverARefusal() {
        assertThat(real.reserve(tier(11L), new BigDecimal("100.01"), "t-4#L0")).isEmpty();
        assertThat(standIn.reserve(tier(11L), new BigDecimal("100.01"), "t-4#L0")).isEmpty();
    }
}
