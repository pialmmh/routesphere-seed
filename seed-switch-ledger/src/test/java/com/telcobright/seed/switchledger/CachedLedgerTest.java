package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.sessionflow.api.CachedLedger;
import com.telcobright.seed.sessionflow.api.CachedLedger.Check;
import com.telcobright.seed.sessionflow.api.CachedLedger.Committed;
import com.telcobright.seed.sessionflow.api.CachedLedger.Refused;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import com.telcobright.seed.switchledger.api.SwitchLedger;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.Books;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C1 · the cached ledger (design §14.4) on a REAL MemLedger and its REAL WAL (a ledger DB with purchases and references, two tiers): reads
 * answer copies; a unit over two tiers is ONE record, its minted keys reach the database; a repeated reference answers the first, after a
 * restart too; a failed check writes nothing; a balance is set only under a check of the value it replaces; the sessions' rows are read
 * only; a type the ledger does not cache is refused in words.
 */
class CachedLedgerTest {

    private static final Set<String> ALL = LedgerLab.EVERY_ENTITY;

    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("res_2")
        .account("btcl", 30, 9, "BDT", "50").account("res_2", 11, 7, "BDT", "100")
        .chargeReferences().purchaseTables()
        .purchase("btcl", 9, 4, "2030-01-01 00:00:00").purchase("res_2", 7, 44, "2030-01-01 00:00:00")
        .reserveRow("btcl", "call-1#L0", 30, 9, "BDT", "5", "2026-10-28T03:00:00")
        .asLedgerDb(List.of("package"), List.of(), 5_000);

    @AfterEach
    void close() { lab.close(); }

    private CachedLedger crud() {
        SwitchLedger ledger = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC(), ALL);
        return ledger.port().cachedLedgerOf("btcl").orElseThrow().withIds((table, highestInUse) -> highestInUse + 1);
    }

    @Test
    void readsAnswerCopies_findFiltersAndOrdersByKey_aWrongNameIsRefusedInWords() {
        lab.start();
        CachedLedger crud = crud();

        PackageAccount copy = crud.get("btcl", PackageAccount.class, 30L).orElseThrow();
        copy.setBalanceAfter(new BigDecimal("999"));

        assertThat(lab.cachedBalance("btcl", 30)).as("the cached row is never handed out").isEqualByComparingTo("50");
        assertThat(crud.get("btcl", PackageAccount.class, 31L)).isEmpty();
        assertThat(crud.find("btcl", LedgerPurchase.class, Map.of("idPartner", 4))).extracting(LedgerPurchase::getId).containsExactly(9L);
        assertThat(crud.find("res_2", PackageAccount.class, Map.of())).extracting(PackageAccount::getId).containsExactly(11L);
        assertThat(crud.get("btcl", PackageAccountReserve.class, "call-1#L0")).as("a session's row is read").isPresent();
        assertThatThrownBy(() -> crud.find("btcl", PackageAccount.class, Map.of("partner", 4))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PackageAccount has no field 'partner'");
        assertThatThrownBy(() -> crud.get("res_9", PackageAccount.class, 1L)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("this ledger holds no tier 'res_9'");
    }

    @Test
    void aUnitOverTwoTiersIsOneRecord_itsMintedKeysReachTheDatabase() {
        lab.start();
        CachedLedger crud = crud();
        long records = lab.walRecords();

        CachedLedger.Unit unit = crud.unit("buy-1");
        long purchase = unit.insert("btcl", purchaseOf(4));
        long account = unit.insert("btcl", accountOf(purchase, "TF_min", "20"));
        long cash = unit.insert("btcl", accountOf(purchase, "BDT", "0"));
        unit.add("res_2", PackageAccount.class, 11L, "balanceAfter", new BigDecimal("-5"));
        Committed done = (Committed) unit.commit();

        assertThat(done.keys()).containsExactly(purchase, account, cash);
        assertThat(purchase).as("above the purchases held (9)").isEqualTo(10L);
        assertThat(account).as("above the accounts held (30)").isEqualTo(31L);
        assertThat(cash).as("above the one the unit minted before").isEqualTo(32L);
        assertThat(done.rows()).hasSize(4);
        assertThat(lab.walRecords()).as("ONE record").isEqualTo(records + 1);
        assertThat(lab.entriesOfTheLastRecord()).as("the purchase, its two accounts, the reseller's account, the reference row").isEqualTo(5);
        PackageAccount reseller = lab.ledger().getEntity("res_2", Books.ACCOUNT, 11L);
        assertThat(reseller.getBalanceAfter()).isEqualByComparingTo("95");
        assertThat(reseller.getBalanceBefore()).as("an account's add moves as a credit does").isEqualByComparingTo("100");
        assertThat(reseller.getLastAmount()).isEqualByComparingTo("-5");
        LedgerLab.await("the write-behind of both tiers", () -> lab.dbBalance("btcl", account) != null && lab.dbBalance("res_2", 11).compareTo(new BigDecimal("95")) == 0);
        assertThat(lab.dbBalance("btcl", account)).isEqualByComparingTo("20");
        assertThat(lab.dbPurchase("btcl", account)).as("under the purchase's minted key").isEqualByComparingTo(String.valueOf(purchase));
    }

    @Test
    void aRepeatedReferenceAnswersTheFirst_writesNothing_afterARestartToo() {
        lab.start();
        long first = ((Committed) unitBuying(crud(), "topup-77").commit()).keys().get(0) instanceof Long id ? id : -1;
        long records = lab.walRecords();

        Committed again = (Committed) unitBuying(crud(), "topup-77").commit();

        assertThat(again.repeated()).isTrue();
        assertThat(again.keys()).containsExactly(first);
        assertThat(lab.walRecords()).as("nothing written twice").isEqualTo(records);
        lab.restart();
        Committed afterTheRestart = (Committed) unitBuying(crud(), "topup-77").commit();
        assertThat(afterTheRestart.repeated()).as("the reference row answers").isTrue();
        assertThat(afterTheRestart.keys()).containsExactly(first);
        assertThat(crud().find("btcl", LedgerPurchase.class, Map.of("idPartner", 4))).as("one purchase made, not two").hasSize(2);
    }

    @Test
    void aFailedCheck_aMissingRow_aRowAlreadyThere_writeNothing() {
        lab.start();
        CachedLedger crud = crud();
        long records = lab.walRecords();

        CachedLedger.Outcome tooLow = crud.unit().add("btcl", PackageAccount.class, 30L, "balanceAfter", BigDecimal.TEN)
            .require("btcl", PackageAccount.class, 30L, Check.atLeast("balanceAfter", new BigDecimal("100"))).commit();
        CachedLedger.Outcome missing = crud.unit().update("btcl", PackageAccount.class, 99L, Map.of("name", "x")).commit();
        CachedLedger.Unit twice = crud.unit();
        twice.insert("btcl", accountOf(9, "BDT", "1"));
        twice.insert("btcl", withKey(accountOf(9, "BDT", "1"), 30L));
        CachedLedger.Outcome there = twice.commit();

        assertThat(((Refused) tooLow).reason()).contains("PackageAccount 30 of btcl: balanceAfter is 60", "below 100");
        assertThat(((Refused) missing).reason()).contains("PackageAccount 99 is not held in btcl");
        assertThat(((Refused) there).reason()).contains("btcl already holds PackageAccount 30");
        assertThat(lab.walRecords()).isEqualTo(records);
        assertThat(lab.cachedBalance("btcl", 30)).isEqualByComparingTo("50");
        assertThat(crud.get("btcl", PackageAccount.class, 31L)).as("the first insert of the refused unit is not there").isEmpty();
    }

    @Test
    void aBalanceIsSetOnlyUnderACheckOfTheValueItReplaces() {
        lab.start();
        CachedLedger crud = crud();

        assertThatThrownBy(() -> crud.unit().update("btcl", PackageAccount.class, 30L, Map.of("balanceAfter", 70)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a balance moves by add");
        assertThat(crud.unit().require("btcl", PackageAccount.class, 30L, Check.equalTo("balanceAfter", 50))
            .update("btcl", PackageAccount.class, 30L, Map.of("balanceAfter", 70)).commit()).isInstanceOf(Committed.class);
        CachedLedger.Outcome stale = crud.unit().require("btcl", PackageAccount.class, 30L, Check.equalTo("balanceAfter", 50))
            .update("btcl", PackageAccount.class, 30L, Map.of("balanceAfter", 80)).commit();

        assertThat(((Refused) stale).reason()).contains("balanceAfter is 70", "not 50");
        assertThat(lab.cachedBalance("btcl", 30)).isEqualByComparingTo("70");
    }

    @Test
    void theSessionsRowsAreReadOnly_aKeyIsNeverUpdated_aKeylessRowNeedsIds() {
        lab.start();
        CachedLedger crud = crud();
        PackageAccountReserve row = crud.get("btcl", PackageAccountReserve.class, "call-1#L0").orElseThrow();
        CachedLedger noIds = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC(), ALL).port().cachedLedgerOf("btcl").orElseThrow();

        assertThatThrownBy(() -> crud.unit().delete("btcl", PackageAccountReserve.class, row.getChannelCallUuid()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("packageaccountreserve rows belong to the sessions");
        assertThatThrownBy(() -> crud.unit().update("btcl", LedgerPurchase.class, 9L, Map.of("id", 12L)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a row's key is not updated");
        assertThatThrownBy(() -> noIds.unit().insert("btcl", purchaseOf(4))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs ids");
    }

    @Test
    void aLedgerThatCachesNoPurchasesRefusesThemInWords() {
        try (LedgerLab plain = LedgerLab.open().schema("btcl").account("btcl", 1, 10, "BDT", "10")) {
            plain.start();
            CachedLedger crud = SwitchLedgers.over(plain.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC()).port().cachedLedgerOf("btcl").orElseThrow();

            assertThat(crud.get("btcl", PackageAccount.class, 1L)).isPresent();
            assertThatThrownBy(() -> crud.find("btcl", LedgerPurchase.class, Map.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("this ledger does not cache packagepurchase");
        }
    }

    @Test
    void aCheckSeesTheMovesNamedBeforeIt() {
        lab.start();
        CachedLedger crud = crud();

        CachedLedger.Outcome outcome = crud.unit().add("btcl", PackageAccount.class, 30L, "balanceAfter", new BigDecimal("60"))
            .require("btcl", PackageAccount.class, 30L, Check.atLeast("balanceAfter", new BigDecimal("100"))).commit();

        assertThat(outcome).as("50 + 60 is at least 100").isInstanceOf(Committed.class);
        assertThat(lab.cachedBalance("btcl", 30)).isEqualByComparingTo("110");
    }

    @Test
    void aPartnersAccountsFollowAUnitsMoves() {
        lab.start();
        SwitchLedger ledger = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC(), ALL);
        CachedLedger crud = ledger.port().cachedLedgerOf("btcl").orElseThrow().withIds((table, highestInUse) -> highestInUse + 1);
        assertThat(ledger.port().accountsOf(buyer(4)).orElseThrow()).extracting(PackageAccount::getId).containsExactly(30L);

        CachedLedger.Unit unit = crud.unit();
        long purchase = unit.insert("btcl", purchaseOf(4));
        long account = unit.insert("btcl", accountOf(purchase, "TF_min", "20"));
        unit.commit();
        CachedLedger.Unit away = crud.unit();
        long othersPurchase = away.insert("btcl", purchaseOf(5));
        away.update("btcl", PackageAccount.class, 30L, Map.of("idpackagePurchase", othersPurchase));
        away.commit();

        assertThat(ledger.port().accountsOf(buyer(4)).orElseThrow()).as("the new account listed; account 30 moved to partner 5's purchase, not")
            .extracting(PackageAccount::getId).containsExactly(account);
        assertThat(ledger.port().accountsOf(buyer(5)).orElseThrow()).extracting(PackageAccount::getId).containsExactly(30L);
    }

    @Test
    void withoutTheReferenceTable_aRepeatIsKnownInMemory_untilARestart() {
        try (LedgerLab noReferences = LedgerLab.open().schema("btcl").account("btcl", 30, 9, "BDT", "50").purchaseTables()
            .purchase("btcl", 9, 4, "2030-01-01 00:00:00").asLedgerDb(List.of("packageaccount", "packageaccountreserve", "packagepurchase"), List.of(), 5_000)) {
            noReferences.start();
            Set<String> held = Set.of(Books.ACCOUNT, Books.RESERVE, Books.PURCHASE);
            CachedLedger crud = SwitchLedgers.over(noReferences.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC(), held).port()
                .cachedLedgerOf("btcl").orElseThrow().withIds((table, highestInUse) -> highestInUse + 1);
            Committed first = (Committed) unitBuying(crud, "r-1").commit();

            Committed again = (Committed) unitBuying(crud, "r-1").commit();
            noReferences.restart();
            CachedLedger afterTheRestart = SwitchLedgers.over(noReferences.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC(), held).port()
                .cachedLedgerOf("btcl").orElseThrow().withIds((table, highestInUse) -> highestInUse + 1);
            Committed forgotten = (Committed) unitBuying(afterTheRestart, "r-1").commit();

            assertThat(again.repeated()).as("remembered in memory").isTrue();
            assertThat(again.keys()).isEqualTo(first.keys());
            assertThat(forgotten.repeated()).as("a restart forgets it: no packageaccountcharge (design §14.6, C2-b)").isFalse();
        }
    }

    private static com.telcobright.rtc.domainmodel.LevelAdmission buyer(int partnerId) {
        com.telcobright.rtc.domainmodel.mysqlentity.Partner partner = new com.telcobright.rtc.domainmodel.mysqlentity.Partner();
        partner.setIdPartner(partnerId);
        return new com.telcobright.rtc.domainmodel.LevelAdmission(0, new com.telcobright.rtc.domainmodel.nonentity.Tenant("btcl"), partner, null);
    }

    private static CachedLedger.Unit unitBuying(CachedLedger crud, String reference) {
        CachedLedger.Unit unit = crud.unit(reference);
        unit.insert("btcl", purchaseOf(4));
        return unit;
    }

    static LedgerPurchase purchaseOf(long partnerId) {
        LedgerPurchase p = LedgerPurchase.made(0, 1, partnerId, LocalDateTime.of(2026, 10, 28, 3, 0), LocalDateTime.of(2026, 11, 28, 3, 0), new BigDecimal("500"));
        p.setId(null);
        return p;
    }

    static PackageAccount accountOf(long purchaseId, String uom, String balance) {
        PackageAccount a = new PackageAccount();
        a.setIdpackagePurchase(purchaseId);
        a.setName(uom);
        a.setUom(uom);
        a.setBalanceBefore(BigDecimal.ZERO);
        a.setBalanceAfter(new BigDecimal(balance));
        a.setLastAmount(new BigDecimal(balance));
        return a;
    }

    private static PackageAccount withKey(PackageAccount a, long id) {
        a.setId(id);
        return a;
    }
}
