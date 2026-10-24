package com.telcobright.seed.switchledger.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.AccountLocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ARCH-0077-A item 5 — the ONE credit primitive ({@code PrepaidServiceWithCompensation.recharge}, 282–382: "any credit written straight to
 * MySQL is destroyed within minutes on an account with call traffic … external writers must therefore call this"): a DELTA on the live
 * balance, under the SAME per-account lock the reserve and the settle take, so it is race-free against a session settling; a reparent moves
 * the account onto a new purchase through the ledger (347–351: "the reparent has to happen HERE and not in the caller's database write").
 */
class CreditTest {

    private final LedgerLab lab = LedgerLab.open().schema("res_2").account("res_2", 11, 1, "OTH_ea", "100");
    private SwitchLedger ledger;

    @BeforeEach
    void start() {
        lab.start();
        ledger = SwitchLedgers.over(lab.ledger(), new SwitchLedgerSettings(60, 500, 2000), Clock.systemUTC());
    }

    @AfterEach
    void stop() { lab.close(); }

    private static LevelAdmission tier() {
        Partner partner = new Partner();
        partner.setIdPartner(701);
        PackageAccount account = new PackageAccount();
        account.setId(11L);
        account.setUom("OTH_ea");
        LevelAdmission level = new LevelAdmission(0, new Tenant("res_2"), partner, account);
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    @Test
    void aDeltaLandsOnTheLiveBalance_whileAReserveIsOpen_andTheSettleStillCloses() {
        LevelAdmission level = tier();
        Reservation held = ledger.port().reserve(level, new BigDecimal("10"), "c-1#L0").orElseThrow();
        level.setDebitReference("c-1#L0");
        level.setReservedAmount(held.reserved());

        Credit.Credited credited = ledger.credit().credit("res_2", 11, new BigDecimal("25"), "topup-1");

        assertThat(credited.before()).isEqualByComparingTo("90");
        assertThat(credited.after()).isEqualByComparingTo("115");
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("115");
        assertThat(lab.cachedRow("res_2", "c-1#L0").getReserveUnit()).as("the open reserve is untouched").isEqualByComparingTo("10");
        TierSettlement done = ledger.port().settle(level, new BigDecimal("4"));
        assertThat(done.balanceAfter()).isEqualByComparingTo("121");
        LedgerLab.await("the write-behind", () -> lab.dbBalance("res_2", 11).compareTo(new BigDecimal("121")) == 0 && lab.dbReserveRows("res_2") == 0);
    }

    @Test
    void theCreditWaitsForTheAccountsLock_theOnePortsVerbsHold() throws Exception {
        AccountLocks locks = AccountLocks.of(lab.ledger(), 2000);
        CompletableFuture<BigDecimal> credited;
        try (AccountLocks.Held held = locks.hold("res_2", 11)) {
            credited = CompletableFuture.supplyAsync(() -> ledger.credit().credit("res_2", 11, new BigDecimal("5"), "topup-2").after());
            Thread.sleep(300);
            assertThat(credited.isDone()).as("the credit waits while a verb holds the account").isFalse();
            assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("100");
        }
        assertThat(credited.get(5, TimeUnit.SECONDS)).isEqualByComparingTo("105");
    }

    @Test
    void reparent_movesTheAccountOntoTheNewPurchase_theBalanceStays_mySqlSeesIt() {
        Credit.Credited moved = ledger.credit().reparent("res_2", 11, 7);

        assertThat(moved.purchaseId()).isEqualTo(7L);
        assertThat(moved.after()).isEqualByComparingTo("100");
        assertThat(lab.ledger().<Long, PackageAccount>getEntity("res_2", "PackageAccount", 11L).getIdpackagePurchase()).isEqualTo(7L);
        LedgerLab.await("the write-behind", () -> lab.dbPurchase("res_2", 11).longValue() == 7L);
        assertThat(lab.dbBalance("res_2", 11)).isEqualByComparingTo("100");
    }

    // ── the debit (F10, the WiFi's clock time) ──

    @Test
    void aDebitTakesTheDeltaOffTheLiveBalance_mySqlSeesIt() {
        Credit.Credited taken = ledger.credit().debit("res_2", 11, new BigDecimal("30"), "clock-1");

        assertThat(taken.before()).isEqualByComparingTo("100");
        assertThat(taken.after()).isEqualByComparingTo("70");
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("70");
        LedgerLab.await("the write-behind", () -> lab.dbBalance("res_2", 11).compareTo(new BigDecimal("70")) == 0);
    }

    @Test
    void aDebitNeverTakesTheBalanceBelowZero_andAnEmptyAccountIsLeftAlone() {
        Credit.Credited first = ledger.credit().debit("res_2", 11, new BigDecimal("150"), "clock-2");
        assertThat(first.after()).as("floored at zero, never negative").isEqualByComparingTo("0");

        Credit.Credited again = ledger.credit().debit("res_2", 11, new BigDecimal("5"), "clock-3");
        assertThat(again.before()).isEqualByComparingTo("0");
        assertThat(again.after()).isEqualByComparingTo("0");
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("0");
        LedgerLab.await("the write-behind", () -> lab.dbBalance("res_2", 11).signum() == 0);
    }

    @Test
    void aDebitWhileAReserveIsOpen_takesTheFreeBalance_andTheSettleStillReturnsTheRest() {
        LevelAdmission level = tier();
        Reservation held = ledger.port().reserve(level, new BigDecimal("10"), "c-9#L0").orElseThrow();
        level.setDebitReference("c-9#L0");
        level.setReservedAmount(held.reserved());

        Credit.Credited taken = ledger.credit().debit("res_2", 11, new BigDecimal("20"), "clock-4");

        assertThat(taken.before()).isEqualByComparingTo("90");
        assertThat(taken.after()).isEqualByComparingTo("70");
        TierSettlement done = ledger.port().settle(level, new BigDecimal("4"));
        assertThat(done.balanceAfter()).as("70 + the 6 the settle returns").isEqualByComparingTo("76");
    }

    @Test
    void theDebitWaitsForTheAccountsLock_theOnePortsVerbsHold() throws Exception {
        AccountLocks locks = AccountLocks.of(lab.ledger(), 2000);
        CompletableFuture<BigDecimal> taken;
        try (AccountLocks.Held held = locks.hold("res_2", 11)) {
            taken = CompletableFuture.supplyAsync(() -> ledger.credit().debit("res_2", 11, new BigDecimal("5"), "clock-5").after());
            Thread.sleep(300);
            assertThat(taken.isDone()).as("the debit waits while a verb holds the account").isFalse();
            assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("100");
        }
        assertThat(taken.get(5, TimeUnit.SECONDS)).isEqualByComparingTo("95");
    }

    @Test
    void aNonPositiveDebit_andAnUnknownAccount_areRefusedInWords() {
        assertThatThrownBy(() -> ledger.credit().debit("res_2", 11, BigDecimal.ZERO, "x")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> ledger.credit().debit("res_2", 11, new BigDecimal("-1"), "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.credit().debit("res_2", 99, BigDecimal.ONE, "x")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not held in schema res_2");
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("100");
    }

    @Test
    void aNonPositiveDelta_andAnUnknownAccount_areRefusedInWords() {
        assertThatThrownBy(() -> ledger.credit().credit("res_2", 11, BigDecimal.ZERO, "x")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> ledger.credit().credit("res_2", 11, new BigDecimal("-1"), "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.credit().credit("res_2", 99, BigDecimal.ONE, "x")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not held in schema res_2");
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("100");
    }
}
