package com.telcobright.seed.prepaid.admin.lab;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.idgen.testkit.InMemoryIdSource;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L2c item 4, once per engine — a purchase through the ledger makes its accounts, and the database holds them under the ids the cache holds.
 * The account is rtc-domain's (its key {@code @GeneratedValue(IDENTITY)}); the ledger is a ledger DB ("package"), built by
 * {@code LedgerDb.register} as the switch ledger's maker builds it; the ids come from the application's {@code IdSource} through
 * {@code PrepaidAdmin.withIds} ({@code mintAbove} the highest id the ledger holds).
 *
 * <pre>
 *   every tier: purchases 1..3 and accounts 1..3 (as an outside writer laid them down); the third tier also account 500 (purchase 300):
 *   each table's ids are minted above the highest that table holds in any tier — purchases above 300, accounts above 500
 *   1  a purchase at the second tier → ONE record; the purchase above 300, its two accounts above 500; the database = the cache
 *   2  the same reference again, in the same process → the first purchase, nothing written twice
 *   3  a hold on its account, a restart → the same accounts by the same ids; the hold found, and settled after the restart
 * </pre>
 * The ledger keeps no reference row ({@code packageaccountcharge} is not adopted — the owner, 2026-10-27: an ad view is ONE call, held
 * once, no retry; an officer's purchase is made once): a repeat is answered from the process's memory only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class PurchaseMakesItsAccountsScenario {

    protected abstract PurchaseLabEngine engine();

    PurchaseLab books;
    String second;
    LedgerPort.Purchased bought;

    @BeforeAll
    void theTiersTheirRowsAndTheLedger() throws Exception {
        PurchaseLabEngine engine = engine();
        assumeTrue(engine.absentBecause() == null, engine.absentBecause());
        books = PurchaseLab.over(engine, new InMemoryIdSource());
        second = books.tier(1);
        books.layDownTheOutsideWritersRows();
        books.start();
    }

    @AfterAll
    void nothingIsLeft() throws Exception {
        if (books != null) books.close();
    }

    /** 1 · ONE record: the purchase and its accounts, minted above every id held; the consumer writes them under those very ids. */
    @Test
    @Order(1)
    void aPurchaseIsOneRecord_itsAccountsKeepTheMintedIds_andTheDatabaseHoldsThem() throws Exception {
        long before = books.walRecords();

        bought = books.admin().purchase(books.tier(0), second, PurchaseLab.CLIENT, PurchaseLab.offer(10), "pay-1");

        assertThat(books.walRecords()).as("ONE record").isEqualTo(before + 1);
        assertThat(bought.purchaseId()).as("above every purchase held (300)").isGreaterThan(300);
        assertThat(bought.accounts()).as("two accounts above every account held (500)").hasSize(2).allMatch(id -> id > 500);
        books.awaitTheDatabaseEqualsTheCache(second);
        assertThat(PurchaseLabRows.accountIds(books.db(), second)).containsExactlyInAnyOrder(1L, 2L, 3L, bought.accounts().get(0), bought.accounts().get(1));
        assertThat(PurchaseLabRows.purchaseIds(books.db(), second)).containsExactlyInAnyOrder(1L, 2L, 3L, bought.purchaseId());
        assertThat(PurchaseLabRows.balance(books.db(), second, bought.accounts().get(1))).as("the minutes, under the cache's id").isEqualByComparingTo("300");
    }

    /** 2 · The same reference again, in the same process: the first purchase, repeated — no record, no row twice. */
    @Test
    @Order(2)
    void aRepeatedReference_answersTheFirstPurchase_nothingWrittenTwice() throws Exception {
        books.awaitTheDatabaseEqualsTheCache(second);
        long records = books.walRecords();
        Set<Long> accounts = PurchaseLabRows.accountIds(books.db(), second);

        LedgerPort.Purchased again = books.admin().purchase(books.tier(0), second, PurchaseLab.CLIENT, PurchaseLab.offer(10), "pay-1");

        assertThat(again.repeated()).isTrue();
        assertThat(again.purchaseId()).isEqualTo(bought.purchaseId());
        assertThat(again.accounts()).isEqualTo(bought.accounts());
        assertThat(books.walRecords()).as("nothing written").isEqualTo(records);
        Thread.sleep(300);
        assertThat(PurchaseLabRows.accountIds(books.db(), second)).as("no account twice").isEqualTo(accounts);
    }

    /** 3 · A hold opened on the new account, a restart: the same accounts by the same ids, the hold found and settled after it. */
    @Test
    @Order(3)
    void aHoldOpenedBeforeARestart_isFoundAndSettledAfterIt_underTheSameIds() throws Exception {
        long cash = bought.accounts().get(0);
        LevelAdmission level = tier(second, cash);
        Reservation held = books.port().reserve(level, new BigDecimal("10"), "call-1#L0").orElseThrow();
        record(level, held, "call-1#L0");
        PurchaseLabRows.await("the hold in the database", () -> PurchaseLabRows.reserveRowsOf(books.db(), second, cash) == 1
            && new BigDecimal("50").compareTo(PurchaseLabRows.balance(books.db(), second, cash)) == 0);
        Set<Long> accountsBefore = PurchaseLabRows.accountIds(books.db(), second);

        books.restart();

        assertThat(new TreeSet<>(books.ledger().<Long, PackageAccount>getAllEntities(second, PurchaseLab.ACCOUNT).keySet()))
            .as("the cache after the restart: the same accounts by the same ids").isEqualTo(accountsBefore);
        assertThat(books.ledger().<String, PackageAccountReserve>getAllEntities(second, PurchaseLab.RESERVE).values())
            .as("the hold, found").anyMatch(row -> row.getIdPackageAccount() == cash);
        TierSettlement settled = books.port().settle(level, new BigDecimal("7"));
        assertThat(settled.returned()).as("10 held, 7 charged").isEqualByComparingTo("3");
        assertThat(settled.balanceAfter()).isEqualByComparingTo("53");
        PurchaseLabRows.await("the settlement in the database", () -> PurchaseLabRows.reserveRowsOf(books.db(), second, cash) == 0
            && new BigDecimal("53").compareTo(PurchaseLabRows.balance(books.db(), second, cash)) == 0);
        books.awaitTheDatabaseEqualsTheCache(second);
    }

    // ---- a tier as the lane names it on a call: its partner, the account it holds ----

    static LevelAdmission tier(String db, long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(PurchaseLab.CLIENT);
        PackageAccount account = new PackageAccount();
        account.setId(accountId);
        account.setUom("BDT");
        LevelAdmission level = new LevelAdmission(0, new Tenant(db), partner, account);
        level.setRate(new BigDecimal("10"));
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    /** What the base records on a level after a hold ({@code AdmissionChain.recordReserve}). */
    static void record(LevelAdmission level, Reservation held, String reference) {
        level.setDebitReference(reference);
        level.setReservedAmount(held.reserved());
        level.setBalanceAfter(held.balanceAfter());
        level.incrementReservationCount();
    }
}
