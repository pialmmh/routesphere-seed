package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.sessionflow.api.CachedLedger;
import com.telcobright.seed.sessionflow.api.CachedLedger.Committed;
import com.telcobright.seed.sessionflow.api.CachedLedger.Refused;
import com.telcobright.seed.sessionflow.internal.PerRootLedger;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.api.CachedLedgerRoads;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.AccountLocks;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.spi.LedgerGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2 · the roads' statuses (200 · 409 · 400 · 403 · 404 · 503) and the row rules on the real MemLedger: a row the database would refuse —
 * an empty required field, more decimals than its column, a purchase deleted under its accounts, an account deleted under a session's
 * reserve — is refused before the WAL; an account already on a purchase the tier lacks (an old row) still takes a move.
 */
class CachedLedgerRoadsTest {

    private static final byte[] SALT = "roads-salt".getBytes(StandardCharsets.UTF_8);

    private final LedgerLab lab = LedgerLab.open().schema("btcl")
        .account("btcl", 30, 9, "BDT", "50").account("btcl", 40, 404, "BDT", "5")
        .chargeReferences().purchaseTables().purchase("btcl", 9, 4, "2030-01-01 00:00:00")
        .reserveRow("btcl", "call-1#L0", 30, 9, "BDT", "5", "2026-10-28T03:00:00")
        .asLedgerDb(List.of("package"), List.of(), 5_000);
    private LedgerPort port;
    private CachedLedgerRoads roads;
    private CachedLedger crud;

    @BeforeEach
    void start() {
        lab.start();
        port = SwitchLedgers.over(lab.ledger(), new SwitchLedgerSettings(60, 500, 300), Clock.systemUTC(), Set.of(Books.ACCOUNT, Books.RESERVE, Books.CHARGE, Books.PURCHASE)).port();
        PerRootLedger ledgers = new PerRootLedger(PerRootLedger::rootByTheChain);
        ledgers.add("btcl", port);
        roads = CachedLedgerRoads.over(ledgers).guard(LedgerGuard.ofTokenHash(SALT, LedgerGuard.sha256(SALT, "t")))
            .ids((table, highestInUse) -> highestInUse + 1)
            .writes("packageaccount", List.of("idpackagePurchase", "name", "uom", "lastAmount", "balanceBefore", "balanceAfter"))
            .writes("packagepurchase", List.of("status"))
            .deletes("packagepurchase").deletes("packageaccount")
            .build();
        crud = port.cachedLedgerOf("btcl").orElseThrow().withIds((table, highestInUse) -> highestInUse + 1);
    }

    @AfterEach
    void close() { lab.close(); }

    @Test
    void theStatuses() {
        String add = "{\"moves\":[{\"op\":\"add\",\"tier\":\"btcl\",\"table\":\"packageaccount\",\"id\":30,\"field\":\"balanceAfter\",\"delta\":1}]}";
        String tooMuch = "{\"moves\":[{\"op\":\"add\",\"tier\":\"btcl\",\"table\":\"packageaccount\",\"id\":30,\"field\":\"balanceAfter\",\"delta\":1},"
            + "{\"op\":\"require\",\"tier\":\"btcl\",\"table\":\"packageaccount\",\"id\":30,\"check\":\"atLeast\",\"field\":\"balanceAfter\",\"value\":1000}]}";

        assertThat(roads.unit("btcl", "Bearer t", add).status()).isEqualTo(200);
        assertThat(roads.unit("btcl", "Bearer t", tooMuch)).satisfies(a -> assertThat(a.status()).isEqualTo(409)).satisfies(a -> assertThat(a.json()).contains("below 1000"));
        assertThat(roads.unit("btcl", "Bearer t", "{not json").status()).isEqualTo(400);
        assertThat(roads.unit("btcl", "Bearer t", "{\"moves\":[{\"op\":\"upsert\",\"tier\":\"btcl\",\"table\":\"packageaccount\"}]}").json()).contains("not 'upsert'");
        assertThat(roads.unit("btcl", "Bearer t", "{\"moves\":[{\"op\":\"update\",\"tier\":\"btcl\",\"table\":\"packageaccount\",\"id\":{\"$key\":0},\"set\":{\"name\":\"x\"}}]}").json())
            .contains("the unit has 0 insert(s) before it");
        assertThat(roads.unit("btcl", "Bearer wrong", add).status()).isEqualTo(403);
        assertThat(roads.unit("btcl", null, add).status()).isEqualTo(403);
        assertThat(roads.unit("ccl", "Bearer t", add).status()).as("a root this application does not keep").isEqualTo(404);
        assertThat(roads.row("btcl", "t", "btcl", "packageaccount", "99").status()).isEqualTo(404);
        assertThat(roads.row("btcl", "t", "btcl", "packageaccountreserve", "call-1#L0").status()).as("not named readable").isEqualTo(403);
        assertThat(lab.cachedBalance("btcl", 30)).isEqualByComparingTo("51");
    }

    @Test
    void aLockNotFreeInTimeIs503_sendItAgainUnderItsReference() throws Exception {
        AccountLocks locks = AccountLocks.of(lab.ledger(), 300);
        String add = "{\"reference\":\"r-503\",\"moves\":[{\"op\":\"add\",\"tier\":\"btcl\",\"table\":\"packageaccount\",\"id\":30,\"field\":\"balanceAfter\",\"delta\":1}]}";
        CachedLedgerRoads.Answer busy;
        try (AccountLocks.Held session = locks.hold("btcl", 30)) {
            busy = CompletableFuture.supplyAsync(() -> roads.unit("btcl", "t", add)).get(5, TimeUnit.SECONDS);   // another thread: the locks are re-entrant
        }

        assertThat(busy.status()).isEqualTo(503);
        assertThat(busy.json()).contains("was not free within");
        assertThat(roads.unit("btcl", "t", add).status()).isEqualTo(200);
        assertThat(roads.unit("btcl", "t", add).json()).contains("\"repeated\":true");
        assertThat(lab.cachedBalance("btcl", 30)).isEqualByComparingTo("51");
    }

    @Test
    void anEmptyRequiredField_moreDecimalsThanTheColumn_areRefusedBeforeTheWal() {
        PackageAccount noUnit = CachedLedgerTest.accountOf(9, "BDT", "1");
        noUnit.setUom(null);
        CachedLedger.Unit first = crud.unit();
        first.insert("btcl", noUnit);
        CachedLedger.Unit second = crud.unit().add("btcl", PackageAccount.class, 30L, "balanceAfter", new BigDecimal("0.0000001"));
        long records = lab.walRecords();

        assertThat(((Refused) first.commit()).reason()).contains("uom is empty");
        assertThat(((Refused) second.commit()).reason()).as("the move itself has 7 decimals").contains("lastAmount 0.0000001 does not fit its column (20 digits, 6 after the point)");
        assertThat(lab.walRecords()).isEqualTo(records);
    }

    @Test
    void onlyWhatTheUnitWritesIsChecked_anOldRowWithNoNameStillTakesAMove_aNewRowWithoutOneIsRefused() {
        lab.execute("UPDATE btcl.packageaccount SET name = NULL WHERE id_packageaccount = 40");     // as an old PostgreSQL row may be
        lab.restart();                                                                              // the cache loads it so
        CachedLedger ledger = SwitchLedgers.over(lab.ledger(), new SwitchLedgerSettings(60, 500, 300), Clock.systemUTC(),
            Set.of(Books.ACCOUNT, Books.RESERVE, Books.CHARGE, Books.PURCHASE)).port().cachedLedgerOf("btcl").orElseThrow().withIds((table, highest) -> highest + 1);
        PackageAccount nameless = CachedLedgerTest.accountOf(9, "BDT", "1");
        nameless.setName(null);
        CachedLedger.Unit insert = ledger.unit();
        insert.insert("btcl", nameless);

        assertThat(((Refused) insert.commit()).reason()).as("a new row: its name may not be empty (RTC-Manager's MySQL template)").contains("name is empty, its column may not be");
        assertThat(ledger.unit().add("btcl", PackageAccount.class, 40L, "balanceAfter", BigDecimal.ONE).commit())
            .as("the old row's empty name is the database's own: its balance still moves").isInstanceOf(Committed.class);
    }

    @Test
    void aPurchaseUnderItsAccounts_anAccountUnderASessionsReserve_areNotDeleted() {
        CachedLedger.Outcome purchase = crud.unit().delete("btcl", LedgerPurchase.class, 9L).commit();
        CachedLedger.Outcome account = crud.unit().delete("btcl", PackageAccount.class, 30L).commit();
        CachedLedger.Outcome both = crud.unit().delete("btcl", PackageAccount.class, 40L).commit();

        assertThat(((Refused) purchase).reason()).contains("account 30 is on it");
        assertThat(((Refused) account).reason()).contains("a session holds a reserve on it (call-1#L0)");
        assertThat(both).as("an account with no reserve").isInstanceOf(Committed.class);
    }

    @Test
    void aUnitInsideAnotherUnitsLocks_isRefused_itsWriteWouldBeUndone() {
        try (AccountLocks.Held session = AccountLocks.of(lab.ledger(), 300).hold("btcl", 30)) {
            assertThatThrownBy(() -> crud.unit().add("btcl", PackageAccount.class, 30L, "balanceAfter", BigDecimal.ONE).commit())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("inside another unit that holds PackageAccount 30 of btcl");
        }
        assertThat(crud.unit().add("btcl", PackageAccount.class, 30L, "balanceAfter", BigDecimal.ONE).commit()).as("after it").isInstanceOf(Committed.class);
    }

    @Test
    void anOldAccountOnAPurchaseItsTierLacks_stillTakesAMove_butIsNotMovedOntoOne() {
        CachedLedger.Outcome add = crud.unit().add("btcl", PackageAccount.class, 40L, "balanceAfter", BigDecimal.ONE).commit();
        CachedLedger.Outcome moved = crud.unit().update("btcl", PackageAccount.class, 30L, Map.of("idpackagePurchase", 404L)).commit();

        assertThat(add).as("account 40 sat on purchase 404 before; nothing new is asked of the database").isInstanceOf(Committed.class);
        assertThat(((Refused) moved).reason()).contains("its purchase 404 is not held in btcl");
        assertThatThrownBy(() -> crud.get("btcl", PackageAccount.class, "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
