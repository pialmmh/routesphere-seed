package com.telcobright.seed.switchledger.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.api.SwitchLedger;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ARCH-0077-A item 3 — the port's own verbs on a REAL MemLedger, asked directly: idempotency, the release, the refusals, the fault, the peek. */
class MemLedgerPortTest {

    private final LedgerLab lab = LedgerLab.open().schema("res_2").account("res_2", 11, 1, "OTH_ea", "100");
    private SwitchLedger ledger;
    private LedgerPort port;

    @BeforeEach
    void start() {
        lab.start();
        ledger = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC());
        port = ledger.port();
    }

    @AfterEach
    void stop() { lab.close(); }

    private static LevelAdmission tier(String db, Long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(701);
        partner.setPartnerName("Unilever");
        PackageAccount account = null;
        if (accountId != null) {
            account = new PackageAccount();
            account.setId(accountId);
            account.setUom("OTH_ea");
        }
        LevelAdmission level = new LevelAdmission(0, new Tenant(db), partner, account);
        level.setRate(new BigDecimal("10"));
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    /** The base records a reserve on its level ({@code AdmissionChain.recordReserve}); a direct test does the same by hand. */
    private static void record(LevelAdmission level, Reservation held, String reference) {
        if (level.getDebitReference() == null) { level.setDebitReference(reference); level.setReservedAmount(held.reserved()); }
        else level.addToTotalReserved(held.reserved());
        level.setBalanceAfter(held.balanceAfter());
        level.incrementReservationCount();
    }

    @Test
    void aRepeatedReference_answersTheFirstResult_andDebitsOnce() {
        LevelAdmission level = tier("res_2", 11L);

        Reservation first = port.reserve(level, new BigDecimal("10"), "p-1#L0").orElseThrow();
        Reservation again = port.reserve(level, new BigDecimal("10"), "p-1#L0").orElseThrow();

        assertThat(first.repeated()).isFalse();
        assertThat(again.repeated()).isTrue();
        assertThat(again.reserved()).isEqualByComparingTo("10");
        assertThat(again.balanceBefore()).isEqualByComparingTo("100");
        assertThat(again.balanceAfter()).isEqualByComparingTo("90");
        assertThat(again.account()).isEqualTo(11L);
        assertThat(again.uom()).isEqualTo("OTH_ea");
        assertThat(lab.cachedBalance("res_2", 11)).as("debited once").isEqualByComparingTo("90");
        assertThat(lab.cachedRow("res_2", "p-1#L0").getReserveUnit()).isEqualByComparingTo("10");
    }

    @Test
    void aBalanceShortOfTheAmount_isEmpty_andNothingMoves() {
        LevelAdmission level = tier("res_2", 11L);

        assertThat(port.reserve(level, new BigDecimal("100.01"), "p-2#L0")).isEmpty();

        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("100");
        assertThat(lab.cachedReserveRows("res_2")).isZero();
        assertThat(port.reserve(level, new BigDecimal("100"), "p-2#L0")).as("exactly the balance can be held").isPresent();
    }

    @Test
    void release_givesTheWholeRowBack_andASecondReleaseIsNothing() {
        LevelAdmission level = tier("res_2", 11L);
        record(level, port.reserve(level, new BigDecimal("10"), "p-3#L0").orElseThrow(), "p-3#L0");
        record(level, port.reserve(level, new BigDecimal("10"), "p-3#L0#W2").orElseThrow(), "p-3#L0#W2");
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("80");

        port.release(level, "a later tier refused");
        port.release(level, "a later tier refused");

        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("100");
        assertThat(lab.cachedReserveRows("res_2")).isZero();
        LedgerLab.await("the write-behind", () -> lab.dbReserveRows("res_2") == 0 && lab.dbBalance("res_2", 11).compareTo(new BigDecimal("100")) == 0);
    }

    @Test
    void settleTwice_answersTheFirstSettlement_andMovesOnce() {
        LevelAdmission level = tier("res_2", 11L);
        record(level, port.reserve(level, new BigDecimal("10"), "p-4#L0").orElseThrow(), "p-4#L0");

        TierSettlement first = port.settle(level, new BigDecimal("7"));
        TierSettlement again = port.settle(level, new BigDecimal("7"));

        assertThat(first.balanceAfter()).isEqualByComparingTo("93");
        assertThat(again).isSameAs(first);
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("93");
    }

    @Test
    void aReferenceIsForgottenWhenItsTierCloses_theNextSessionMayReuseIt() {
        LevelAdmission level = tier("res_2", 11L);
        record(level, port.reserve(level, new BigDecimal("10"), "p-5#L0").orElseThrow(), "p-5#L0");
        port.settle(level, new BigDecimal("10"));

        Reservation fresh = port.reserve(tier("res_2", 11L), new BigDecimal("10"), "p-5#L0").orElseThrow();

        assertThat(fresh.repeated()).as("a new session under an old reference is a new reserve").isFalse();
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("80");
    }

    @Test
    void noAccount_isTheRefusalNO_ACCOUNT_inWords() {
        assertThatThrownBy(() -> port.reserve(tier("res_2", null), new BigDecimal("10"), "p-6#L0"))
            .isInstanceOf(LedgerRefusal.class)
            .hasMessageContaining("NO_ACCOUNT").hasMessageContaining("names no package account")
            .extracting(e -> ((LedgerRefusal) e).code()).isEqualTo(MemLedgerPort.NO_ACCOUNT);
    }

    @Test
    void anAccountTheLedgerDoesNotHold_isTheRefusalACCOUNT_NOT_FOUND() {
        assertThatThrownBy(() -> port.reserve(tier("res_2", 99L), new BigDecimal("10"), "p-7#L0"))
            .isInstanceOf(LedgerRefusal.class).hasMessageContaining("not held in schema res_2");
    }

    @Test
    void aLedgerThatDoesNotAnswer_isAFault_neverARefusal() {
        lab.ledger().shutdown();

        assertThatThrownBy(() -> port.reserve(tier("res_2", 11L), new BigDecimal("10"), "p-8#L0"))
            .isInstanceOf(LedgerFault.class).isNotInstanceOf(LedgerRefusal.class);
    }

    @Test
    void anUnknownSchema_isAFault() {
        assertThatThrownBy(() -> port.reserve(tier("res_9", 11L), new BigDecimal("10"), "p-9#L0"))
            .isInstanceOf(LedgerFault.class);
    }

    @Test
    void balanceOf_peeksTheLiveRow_noWrite() {
        LevelAdmission level = tier("res_2", 11L);
        assertThat(ledger.balances().balanceOf(level)).contains(new BigDecimal("100.000000"));
        port.reserve(level, new BigDecimal("30"), "p-10#L0");

        Optional<BigDecimal> peeked = ledger.balances().balanceOf(level);

        assertThat(peeked).isPresent();
        assertThat(peeked.get()).isEqualByComparingTo("70");
        assertThat(ledger.balances().balanceOf(tier("res_2", null))).isEmpty();
        assertThat(ledger.balances().balanceOf(tier("res_2", 99L))).isEmpty();
        assertThat(port.slowestAnswerMs()).as("in the process").isZero();
    }
}
