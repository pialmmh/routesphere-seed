package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2b item 4 · the seed's part of a purchase through the ledger, on the cache and the REAL WAL (a ledger DB with purchases and references):
 * ONE record with every row — the purchase, an account per bucket with its opening balance, its reference row — the ids asked above what
 * the ledger holds; the partner's accounts then name them (S4); the same reference again → the first purchase, no record, no second row.
 * L2c: the write-behind lands under the SAME ids — the ledger DB names rtc-domain's {@code @GeneratedValue} account key as minted by the
 * caller, so the consumer writes the account's own id (the lab's table has no counter of its own: a key left out could not be written).
 */
class PurchasesTest {

    private final LedgerLab lab = LedgerLab.open().schema("btcl").account("btcl", 30, 9, "BDT", "5").chargeReferences().purchaseTables()
        .purchase("btcl", 9, 4, "2030-01-01 00:00:00").asLedgerDb(List.of("package"), List.of("packageaccountreserve"), 5_000);
    private final AtomicLong asked = new AtomicLong();

    @AfterEach
    void close() { lab.close(); }

    @Test
    void aPurchaseIsOneRecordWithEveryRow_aRepeatIsTheFirst_andThePartnersAccountsNameIt() {
        MemLedger memLedger = lab.start();
        Books books = new Books(memLedger, Clock.systemUTC(), Set.of(Books.ACCOUNT, Books.RESERVE, Books.CHARGE, Books.PURCHASE));
        Purchases purchases = new Purchases(books);
        long before = lab.walRecords();

        LedgerPort.Purchased bought = purchases.purchase(buyer(4), offer(), "pay-9", this::above);

        assertThat(lab.walRecords()).as("ONE record").isEqualTo(before + 1);
        assertThat(lab.entriesOfTheLastRecord()).as("the purchase, two accounts, the reference row").isEqualTo(4);
        assertThat(bought.purchaseId()).as("above the purchases held (9)").isGreaterThan(9);
        assertThat(bought.accounts()).as("above the accounts held (30)").allMatch(id -> id > 30).hasSize(2);
        LedgerPurchase purchase = memLedger.getEntity("btcl", Books.PURCHASE, bought.purchaseId());
        assertThat(purchase.getIdPartner()).isEqualTo(4L);
        PackageAccount minutes = memLedger.getEntity("btcl", Books.ACCOUNT, bought.accounts().get(1));
        assertThat(minutes.getBalanceAfter()).isEqualByComparingTo("300");
        assertThat(minutes.getIdpackagePurchase()).isEqualTo(bought.purchaseId());
        assertThat(purchases.accountsOf(buyer(4)).orElseThrow()).extracting(PackageAccount::getId)
            .containsExactly(30L, bought.accounts().get(0), bought.accounts().get(1));
        long minutesId = bought.accounts().get(1);
        LedgerLab.await("the minutes account in the database under its minted id", () -> lab.dbBalance("btcl", minutesId) != null);
        assertThat(lab.dbBalance("btcl", minutesId)).as("the database row of the cache's id").isEqualByComparingTo("300");
        assertThat(lab.dbPurchase("btcl", minutesId)).as("of the purchase the cache holds").isEqualByComparingTo(String.valueOf(bought.purchaseId()));

        LedgerPort.Purchased again = purchases.purchase(buyer(4), offer(), "pay-9", this::above);

        assertThat(again.repeated()).isTrue();
        assertThat(again.purchaseId()).isEqualTo(bought.purchaseId());
        assertThat(lab.walRecords()).as("nothing written twice").isEqualTo(before + 1);
        assertThat(purchases.accountsOf(buyer(4)).orElseThrow()).hasSize(3);
    }

    /** The application's ids: one above what the ledger holds (seed-idgen's mintAbove, as the admin road adapts it). */
    private long above(String table, long highestInUse) {
        asked.incrementAndGet();
        return highestInUse + 1;
    }

    private static LedgerPort.Offer offer() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 6, 0);
        return new LedgerPort.Offer(7, "Combo", new BigDecimal("99.00"), now, now.plusDays(30),
            List.of(new LedgerPort.Bucket("BDT", new BigDecimal("60")), new LedgerPort.Bucket("TF_min", new BigDecimal("300"))));
    }

    private static LevelAdmission buyer(int partnerId) {
        Partner partner = new Partner();
        partner.setIdPartner(partnerId);
        return new LevelAdmission(0, new Tenant("btcl"), partner, null);
    }
}
