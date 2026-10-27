package com.telcobright.seed.switchledger;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.CachedLedger;
import com.telcobright.seed.sessionflow.api.CachedLedger.Committed;
import com.telcobright.seed.sessionflow.api.CachedLedger.Outcome;
import com.telcobright.seed.sessionflow.api.CachedLedger.Refused;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.AccountLocks;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.seed.switchledger.internal.OwnedMemLedgerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C1 · the cached ledger takes the locks the sessions take (design §14.4): a unit waits while a session holds its account; an add and a
 * session's charges on the same account never lose a move; units that ask the same {@code requireNone} run one after the other; a unit
 * through the owned port waits while a new tier loads (the gate).
 */
class CachedLedgerLocksTest {

    private static final Set<String> ALL = Set.of(Books.ACCOUNT, Books.RESERVE, Books.CHARGE, Books.PURCHASE);

    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("res_2")
        .account("btcl", 30, 9, "BDT", "50").account("res_2", 11, 7, "BDT", "100")
        .chargeReferences().purchaseTables()
        .purchase("btcl", 9, 4, "2030-01-01 00:00:00").purchase("res_2", 7, 44, "2030-01-01 00:00:00")
        .asLedgerDb(List.of("package"), List.of(), 5_000);

    @AfterEach
    void close() { lab.close(); }

    @Test
    void aUnitWaitsWhileASessionHoldsTheAccount() throws Exception {
        MemLedger memLedger = lab.start();
        CachedLedger crud = portOver(memLedger).cachedLedgerOf("res_2").orElseThrow();
        AccountLocks locks = AccountLocks.of(memLedger, 2000);
        CompletableFuture<Outcome> added;
        try (AccountLocks.Held session = locks.hold("res_2", 11)) {
            added = CompletableFuture.supplyAsync(() -> crud.unit().add("res_2", PackageAccount.class, 11L, "balanceAfter", BigDecimal.ONE).commit());
            Thread.sleep(300);
            assertThat(added.isDone()).as("the unit waits for the session's lock of the account").isFalse();
        }
        assertThat(added.get(5, TimeUnit.SECONDS)).isInstanceOf(Committed.class);
        assertThat(lab.cachedBalance("res_2", 11)).isEqualByComparingTo("101");
    }

    @Test
    void addsAndASessionsChargesOnOneAccountNeverLoseAMove() throws Exception {
        MemLedger memLedger = lab.start();
        LedgerPort port = portOver(memLedger);
        CachedLedger crud = port.cachedLedgerOf("res_2").orElseThrow();
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<Void> charges = CompletableFuture.runAsync(() -> {
            await(go);
            for (int i = 0; i < 40; i++) port.charge(level(), BigDecimal.ONE, "sms-" + i + "#L0").orElseThrow();
        });
        CompletableFuture<Void> adds = CompletableFuture.runAsync(() -> {
            await(go);
            for (int i = 0; i < 40; i++) assertThat(crud.unit().add("res_2", PackageAccount.class, 11L, "balanceAfter", new BigDecimal("2")).commit()).isInstanceOf(Committed.class);
        });

        go.countDown();
        CompletableFuture.allOf(charges, adds).get(30, TimeUnit.SECONDS);

        assertThat(lab.cachedBalance("res_2", 11)).as("100 − 40 × 1 + 40 × 2, every move kept").isEqualByComparingTo("140");
        LedgerLab.await("the write-behind", () -> lab.dbBalance("res_2", 11).compareTo(new BigDecimal("140")) == 0);
    }

    @Test
    void unitsAskingTheSameRequireNoneRunOneAfterTheOther() throws Exception {
        MemLedger memLedger = lab.start();
        CachedLedger crud = portOver(memLedger).cachedLedgerOf("btcl").orElseThrow().withIds((table, highest) -> highest + 1);
        AccountLocks locks = AccountLocks.of(memLedger, 2000);
        Map<String, ?> welcome = Map.of("idpackagePurchase", 9L, "uom", "TF_min");
        CompletableFuture<Outcome> first, second;
        try (AccountLocks.Held purchaseHeld = locks.holdAll(List.of(AccountLocks.Key.row("btcl", Books.PURCHASE, 9L)))) {
            first = CompletableFuture.supplyAsync(() -> crud.unit().requireNone("btcl", PackageAccount.class, welcome)
                .update("btcl", LedgerPurchase.class, 9L, Map.of("onSelectPriority", 1)).commit());
            Thread.sleep(200);
            second = CompletableFuture.supplyAsync(() -> {
                CachedLedger.Unit grant = crud.unit().requireNone("btcl", PackageAccount.class, welcome);
                grant.insert("btcl", CachedLedgerTest.accountOf(9, "TF_min", "60"));
                return grant.commit();
            });
            Thread.sleep(300);
            assertThat(first.isDone()).as("the first holds the question and waits for the purchase").isFalse();
            assertThat(second.isDone()).as("the second asks the same question: it waits for the first").isFalse();
        }
        assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(Committed.class);
        assertThat(second.get(5, TimeUnit.SECONDS)).as("then the second: no row matched yet").isInstanceOf(Committed.class);
        assertThat(crud.find("btcl", PackageAccount.class, welcome)).hasSize(1);
    }

    @Test
    void twoGrantsOfOneWelcome_oneIsWritten() throws Exception {
        MemLedger memLedger = lab.start();
        CachedLedger crud = portOver(memLedger).cachedLedgerOf("btcl").orElseThrow().withIds((table, highest) -> highest + 1);
        Map<String, ?> welcome = Map.of("idpackagePurchase", 9L, "uom", "TF_min");
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Outcome>> grants = List.of(grant(crud, welcome, go), grant(crud, welcome, go));

        go.countDown();

        List<Outcome> outcomes = List.of(grants.get(0).get(5, TimeUnit.SECONDS), grants.get(1).get(5, TimeUnit.SECONDS));
        assertThat(outcomes).filteredOn(o -> o instanceof Committed).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof Refused).singleElement()
            .satisfies(o -> assertThat(((Refused) o).reason()).contains("matches", "(require none)"));
        assertThat(crud.find("btcl", PackageAccount.class, welcome)).hasSize(1);
    }

    @Test
    void aUnitThroughTheOwnedPortWaitsWhileANewTierLoads() throws Exception {
        MemLedger memLedger = lab.start();
        MemLedgerPort port = (MemLedgerPort) SwitchLedgers.over(memLedger, SwitchLedgerSettings.standard(), Clock.systemUTC(), ALL).port();
        CountDownLatch loading = new CountDownLatch(1), release = new CountDownLatch(1);
        OwnedMemLedgerPort owned = new OwnedMemLedgerPort(port, memLedger, Set.of("btcl", "res_2"), tier -> {
            loading.countDown();
            await(release);
            return true;
        });
        CachedLedger crud = owned.cachedLedgerOf("btcl").orElseThrow();
        CompletableFuture<Void> load = CompletableFuture.runAsync(() -> owned.tierAdded("res_9"));
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Outcome> added = CompletableFuture.supplyAsync(() -> crud.unit().add("btcl", PackageAccount.class, 30L, "balanceAfter", BigDecimal.ONE).commit());
        Thread.sleep(300);
        assertThat(added.isDone()).as("no unit runs while a tier loads").isFalse();
        release.countDown();

        load.get(5, TimeUnit.SECONDS);
        assertThat(added.get(5, TimeUnit.SECONDS)).isInstanceOf(Committed.class);
        assertThat(lab.cachedBalance("btcl", 30)).isEqualByComparingTo("51");
    }

    private static CompletableFuture<Outcome> grant(CachedLedger crud, Map<String, ?> welcome, CountDownLatch go) {
        return CompletableFuture.supplyAsync(() -> {
            await(go);
            CachedLedger.Unit unit = crud.unit().requireNone("btcl", PackageAccount.class, welcome);
            unit.insert("btcl", CachedLedgerTest.accountOf(9, "TF_min", "60"));
            return unit.commit();
        });
    }

    private static LedgerPort portOver(MemLedger memLedger) {
        return SwitchLedgers.over(memLedger, SwitchLedgerSettings.standard(), Clock.systemUTC(), ALL).port();
    }

    private static LevelAdmission level() {
        Partner partner = new Partner();
        partner.setIdPartner(44);
        PackageAccount account = new PackageAccount();
        account.setId(11L);
        return new LevelAdmission(0, new Tenant("res_2"), partner, account);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
