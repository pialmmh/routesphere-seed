package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.CachedLedger;
import com.telcobright.seed.sessionflow.api.CachedLedger.Check;
import com.telcobright.seed.sessionflow.api.CachedLedger.Committed;
import com.telcobright.seed.sessionflow.api.CachedLedger.Refused;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.Books;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C1 · TelcoREST's writes (RTC-Manager e20ea766, design §14.3) as cached-ledger units — the API is enough for each, ONE record each: the
 * top-up (a purchase, the TopUp account moved onto it, the amount added; a repeated payment answers the first purchase); an upgrade's
 * carry-over (the old balance checked as read, so a session's move meanwhile refuses it — read again, then it lands); a welcome grant once;
 * a postpaid limit lowered (today {@code CANNOT_DEBIT}). TelcoREST's own rules (which purchase, how much) stay in the test, as they stay in
 * TelcoREST. The contract tests against TelcoREST's JSON are C2's.
 */
class TelcoRestWritesAsUnitsTest {

    private final LedgerLab lab = LedgerLab.open().schema("btcl")
        .account("btcl", 30, 9, "BDT", "50").account("btcl", 31, 8, "BDT", "1000")
        .chargeReferences().purchaseTables()
        .purchase("btcl", 9, 4, "2030-01-01 00:00:00").purchase("btcl", 8, 5, "2030-01-01 00:00:00")
        .asLedgerDb(List.of("package"), List.of(), 5_000);
    private LedgerPort port;
    private CachedLedger crud;

    @BeforeEach
    void start() {
        lab.start();
        port = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC(), LedgerLab.EVERY_ENTITY).port();
        crud = port.cachedLedgerOf("btcl").orElseThrow().withIds((table, highest) -> highest + 1);
    }

    @AfterEach
    void close() { lab.close(); }

    @Test
    void aTopUpIsOneRecord_theAccountMovesOntoTheNewPurchase_aRepeatedPaymentIsTheFirst() {
        long records = lab.walRecords();
        PackageAccount topUp = crud.find("btcl", PackageAccount.class, Map.of("idpackagePurchase", 9L, "uom", "BDT")).get(0);

        Committed done = (Committed) topUp(topUp.getId(), "pay-1").commit();

        long purchase = (Long) done.keys().get(0);
        assertThat(lab.walRecords()).isEqualTo(records + 1);
        assertThat(lab.entriesOfTheLastRecord()).as("the purchase, the account, the reference row").isEqualTo(3);
        PackageAccount after = crud.get("btcl", PackageAccount.class, 30L).orElseThrow();
        assertThat(after.getIdpackagePurchase()).isEqualTo(purchase);
        assertThat(after.getBalanceAfter()).isEqualByComparingTo("550");
        assertThat(after.getBalanceBefore()).isEqualByComparingTo("50");
        assertThat(after.getLastAmount()).isEqualByComparingTo("500");
        Committed again = (Committed) topUp(topUp.getId(), "pay-1").commit();
        assertThat(again.repeated()).isTrue();
        assertThat(again.keys()).as("TelcoREST's AlreadyApplied(purchase)").containsExactly(purchase);
        assertThat(lab.cachedBalance("btcl", 30)).isEqualByComparingTo("550");
    }

    @Test
    void anUpgradesCarryOverIsRefusedWhenASessionMovedTheOldBalance_thenLandsOnTheBalanceReadAgain() {
        BigDecimal read = crud.get("btcl", PackageAccount.class, 30L).orElseThrow().getBalanceAfter();
        port.charge(level(30), BigDecimal.TEN, "call-9#L0").orElseThrow();

        CachedLedger.Outcome stale = upgrade(read).commit();
        CachedLedger.Outcome fresh = upgrade(crud.get("btcl", PackageAccount.class, 30L).orElseThrow().getBalanceAfter()).commit();

        assertThat(((Refused) stale).reason()).contains("PackageAccount 30 of btcl: balanceAfter is 40", "not 50");
        Committed done = (Committed) fresh;
        long newAccount = (Long) done.keys().get(1);
        assertThat(lab.cachedBalance("btcl", newAccount)).as("the plan's 100 + the 40 carried over").isEqualByComparingTo("140");
        assertThat(crud.get("btcl", LedgerPurchase.class, 9L).orElseThrow().getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    void aWelcomeIsGrantedOnce() {
        Map<String, ?> welcome = Map.of("idpackagePurchase", 9L, "uom", "TF_min");

        CachedLedger.Outcome first = grant(welcome), second = grant(welcome);

        assertThat(first).isInstanceOf(Committed.class);
        assertThat(((Refused) second).reason()).as("TelcoREST's ALREADY_GRANTED").contains("(require none)");
    }

    @Test
    void aPostpaidLimitIsLowered_theAvailableBalanceWithIt() {
        BigDecimal available = crud.get("btcl", PackageAccount.class, 31L).orElseThrow().getBalanceAfter();
        BigDecimal oldLimit = new BigDecimal("1000"), newLimit = new BigDecimal("600");
        BigDecimal newAvailable = available.add(newLimit.subtract(oldLimit)).max(BigDecimal.ZERO).min(newLimit);

        CachedLedger.Unit unit = crud.unit("postpaid:5:600");
        long purchase = unit.insert("btcl", CachedLedgerTest.purchaseOf(5));
        unit.require("btcl", PackageAccount.class, 31L, Check.equalTo("balanceAfter", available))
            .update("btcl", PackageAccount.class, 31L, Map.of("idpackagePurchase", purchase, "balanceAfter", newAvailable, "lastAmount", oldLimit.subtract(newLimit)));

        assertThat(unit.commit()).isInstanceOf(Committed.class);
        assertThat(lab.cachedBalance("btcl", 31)).isEqualByComparingTo("600");
        LedgerLab.await("the write-behind", () -> lab.dbBalance("btcl", 31).compareTo(new BigDecimal("600")) == 0);
    }

    /** TelcoREST's top-up: a new purchase (priority 2), the TopUp account moved onto it, price + vat added — ONE unit, the payment its reference. */
    private CachedLedger.Unit topUp(long account, String paymentRef) {
        CachedLedger.Unit unit = crud.unit("topup:" + paymentRef);
        LedgerPurchase purchase = CachedLedgerTest.purchaseOf(4);
        purchase.setPriority(2);
        long id = unit.insert("btcl", purchase);
        return unit.update("btcl", PackageAccount.class, account, Map.of("idpackagePurchase", id))
            .add("btcl", PackageAccount.class, account, "balanceAfter", new BigDecimal("500"));
    }

    /** TelcoREST's upgrade: the old purchase INACTIVE, a new one with its account — the plan's 100 + the old balance as read. */
    private CachedLedger.Unit upgrade(BigDecimal oldBalanceAsRead) {
        CachedLedger.Unit unit = crud.unit();
        unit.require("btcl", PackageAccount.class, 30L, Check.equalTo("balanceAfter", oldBalanceAsRead))
            .update("btcl", LedgerPurchase.class, 9L, Map.of("status", "INACTIVE"));
        long purchase = unit.insert("btcl", CachedLedgerTest.purchaseOf(4));
        unit.insert("btcl", CachedLedgerTest.accountOf(purchase, "BDT", new BigDecimal("100").add(oldBalanceAsRead).toPlainString()));
        return unit;
    }

    private CachedLedger.Outcome grant(Map<String, ?> welcome) {
        CachedLedger.Unit unit = crud.unit().requireNone("btcl", PackageAccount.class, welcome);
        unit.insert("btcl", CachedLedgerTest.accountOf(9, "TF_min", "60"));
        return unit.commit();
    }

    private static LevelAdmission level(long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(4);
        PackageAccount account = new PackageAccount();
        account.setId(accountId);
        return new LevelAdmission(0, new Tenant("btcl"), partner, account);
    }
}
