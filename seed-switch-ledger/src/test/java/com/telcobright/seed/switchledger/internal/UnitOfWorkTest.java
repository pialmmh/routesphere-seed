package com.telcobright.seed.switchledger.internal;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.switchledger.LedgerLab;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2b item 1 · ONE write path: a unit of work over the REAL MemLedger (H2, nothing listening) is ONE {@code execCrud(WALEntryBatch)} —
 * counted as records of the ledger's own WAL by the lab's tailer, never a mock. Three tiers in one unit = one record; a refused entry =
 * nothing in any tier and no record; a row moved twice in one unit = ONE post-image, the second move reading the first FROM THE UNIT.
 */
class UnitOfWorkTest {

    private static final List<String> TIERS = List.of("btcl", "res_2", "res_3");

    private LedgerLab lab;
    private Books books;

    @BeforeEach
    void open() {
        lab = LedgerLab.open();
        for (String tier : TIERS) lab.schema(tier).account(tier, 1, 10, "BDT", "10");
        lab.start();
        books = new Books(lab.ledger(), Clock.systemUTC());
    }

    @AfterEach
    void close() { lab.close(); }

    @Test
    void aUnitOverThreeTiers_isOneRecordOfTheRealWal_andLandsInEveryTier() {
        long before = lab.walRecords();
        UnitOfWork unit = books.unit();
        for (String tier : TIERS) holdOne(unit, tier, "u1#" + tier);

        unit.commit("hold one unit at three tiers");

        assertThat(lab.walRecords()).as("ONE record for the three tiers").isEqualTo(before + 1);
        assertThat(lab.entriesOfTheLastRecord()).as("an account and a reserve row per tier").isEqualTo(6);
        for (String tier : TIERS) {
            assertThat(lab.cachedBalance(tier, 1)).isEqualByComparingTo("9");
            assertThat(lab.cachedRow(tier, "u1#" + tier).getReserveUnit()).isEqualByComparingTo("1");
            LedgerLab.await(tier + " written behind", () -> new BigDecimal("9").compareTo(lab.dbBalance(tier, 1)) == 0 && lab.dbReserveRows(tier) == 1);
        }
    }

    @Test
    void aRefusedEntry_isAFaultInWords_noTierChanged_noRecord() {
        long before = lab.walRecords();
        UnitOfWork unit = books.unit();
        holdOne(unit, "btcl", "u2#btcl");
        holdOne(unit, "res_2", "u2#res_2");
        unit.update("res_3", Books.ACCOUNT, 999L, account(999, "5"));             // an account res_3 does not hold

        assertThatThrownBy(() -> unit.commit("hold at three tiers"))
            .isInstanceOf(LedgerFault.class)
            .hasMessageContaining("the ledger refused to hold at three tiers")
            .hasMessageContaining("nothing changed")
            .hasMessageContaining("UPDATE of a row the cache does not hold");

        assertThat(lab.walRecords()).as("no record").isEqualTo(before);
        for (String tier : TIERS) {
            assertThat(lab.cachedBalance(tier, 1)).as(tier + " untouched").isEqualByComparingTo("10");
            assertThat(lab.cachedReserveRows(tier)).as(tier + ": no reserve row").isZero();
        }
    }

    @Test
    void theSameRowTwiceInOneUnit_isOnePostImage_theSecondMoveReadsTheFirstFromTheUnit() {
        long before = lab.walRecords();
        UnitOfWork unit = books.unit();
        PackageAccount live = books.liveAccount(unit, "btcl", 1);
        books.moveBalance(unit, "btcl", live, new BigDecimal("9"), new BigDecimal("-1"));
        books.openReserveRow(unit, "btcl", "u3", live, BigDecimal.ONE);
        PackageAccount staged = books.liveAccount(unit, "btcl", 1);
        books.moveBalance(unit, "btcl", staged, Books.balanceOf(staged).subtract(new BigDecimal("1.5")), new BigDecimal("-1.5"));
        books.growReserveRow(unit, "btcl", books.reserveRow(unit, "btcl", "u3"), new BigDecimal("1.5"));

        assertThat(lab.cachedBalance("btcl", 1)).as("the cache is not touched before the commit").isEqualByComparingTo("10");
        assertThat(lab.cachedRow("btcl", "u3")).isNull();
        unit.commit("two moves of one account and one row");

        assertThat(lab.walRecords()).isEqualTo(before + 1);
        assertThat(lab.entriesOfTheLastRecord()).as("the account once, the row once").isEqualTo(2);
        assertThat(lab.cachedBalance("btcl", 1)).as("both moves").isEqualByComparingTo("7.5");
        assertThat(lab.cachedRow("btcl", "u3").getReserveUnit()).as("opened and grown: one INSERT").isEqualByComparingTo("2.5");
    }

    @Test
    void anInsertDeletedInTheSameUnit_writesNothing_andARowMovedAfterItsDelete_isRefusedInWords() {
        long before = lab.walRecords();
        UnitOfWork unit = books.unit();
        books.openReserveRow(unit, "btcl", "u4", books.liveAccount(unit, "btcl", 1), BigDecimal.ONE);
        books.deleteReserveRow(unit, "btcl", "u4");
        assertThat(unit.isEmpty()).isTrue();
        unit.commit("open and close a row");
        assertThat(lab.walRecords()).as("nothing to write").isEqualTo(before);

        UnitOfWork other = books.unit();
        other.delete("btcl", Books.ACCOUNT, 1L);
        assertThatThrownBy(() -> other.update("btcl", Books.ACCOUNT, 1L, account(1, "3")))
            .isInstanceOf(LedgerFault.class).hasMessageContaining("cannot UPDATE the row PackageAccount 1 of btcl after it DELETEd it");
    }

    private void holdOne(UnitOfWork unit, String tier, String key) {
        PackageAccount live = books.liveAccount(unit, tier, 1);
        books.openReserveRow(unit, tier, key, live, BigDecimal.ONE);
        books.moveBalance(unit, tier, live, Books.balanceOf(live).subtract(BigDecimal.ONE), BigDecimal.ONE.negate());
    }

    private static PackageAccount account(long id, String balance) {
        PackageAccount a = new PackageAccount();
        a.setId(id);
        a.setIdpackagePurchase(10L);
        a.setName("BDT-" + id);
        a.setUom("BDT");
        a.setLastAmount(BigDecimal.ZERO);
        a.setBalanceBefore(new BigDecimal(balance));
        a.setBalanceAfter(new BigDecimal(balance));
        return a;
    }
}
