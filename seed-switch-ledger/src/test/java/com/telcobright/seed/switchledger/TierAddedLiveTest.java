package com.telcobright.seed.switchledger;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.AddTierLive;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.seed.switchledger.internal.OwnedMemLedgerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-E item 13 · a reseller tier created live reaches its root's switch ledger (R10): ONE new tier schema loaded into the running MemLedger
 * (mem-ledger's {@code addDatabase(schema)}), between the root's money moves — never under a session's reserve —, once; a tier the ledger was
 * built over is nothing. L2b: the seed's mem-ledger (main 6523614) has the verb — a plain call, no stand-in. MemLedger on H2 in memory.
 */
class TierAddedLiveTest {

    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("btcl_res_5");

    @AfterEach
    void close() throws Exception { lab.close(); }

    private static Tenant btcl() {
        return new TenantTreeBuilder().root("btcl").partner(1, "client", PartnerType.CUSTOMER).and().build();
    }

    /** L2b item 1 · the seed's mem-ledger (main 6523614) has {@code addDatabase}: a plain call loads the one new tier, its rows served at once. */
    @Test
    void theMemLedger_loadsTheOneNewTierLive_itsRowsServedAtOnce() {
        MemLedger memLedger = lab.start();
        lab.schema("btcl_res_9").account("btcl_res_9", 901, 90, "BDT", "12.5");

        assertThat(AddTierLive.of(memLedger).load("btcl_res_9")).isTrue();

        assertThat(memLedger.getRegisteredDatabases()).contains("btcl_res_9");
        assertThat(lab.cachedBalance("btcl_res_9", 901)).isEqualByComparingTo("12.5");
    }

    @Test
    void aTierAlreadyRegistered_isNothing_andATierWithoutTables_isSaidInWords() {
        MemLedger memLedger = lab.start();

        assertThat(AddTierLive.of(memLedger).load("btcl_res_5")).as("built over at the start: nothing").isFalse();
        assertThatThrownBy(() -> AddTierLive.of(memLedger).load("btcl_res_77"))
            .hasMessageContaining("the MemLedger did not load the tier schema 'btcl_res_77'");
        assertThat(memLedger.getRegisteredDatabases()).doesNotContain("btcl_res_77");
    }

    @Test
    void theRootsPort_loadsANewTierOnce_neverUnderAMoneyMove_andABuiltOverTierIsNothing() throws Exception {
        MemLedger memLedger = lab.start();
        MemLedgerPort port = (MemLedgerPort) SwitchLedgers.memLedger(memLedger, SwitchLedgerSettings.standard(), Clock.systemUTC());
        List<String> loads = new CopyOnWriteArrayList<>();
        CountDownLatch loading = new CountDownLatch(1), letGo = new CountDownLatch(1);
        OwnedMemLedgerPort owned = new OwnedMemLedgerPort(port, memLedger, Set.of("btcl", "btcl_res_5"), tier -> {
            loads.add(tier);
            loading.countDown();
            awaitQuietly(letGo);
            return true;
        });
        Tenant root = btcl();
        LevelAdmission level = new LevelAdmission(0, root, root.getContext().getPartners().get(1), null);

        Thread loader = new Thread(() -> owned.tierAdded("btcl_res_9"));
        loader.start();
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<?> moneyRead = CompletableFuture.supplyAsync(() -> owned.balanceOf(level));
        Thread.sleep(200);
        assertThat(moneyRead).as("no money move of this root while its new tier loads").isNotDone();
        letGo.countDown();
        moneyRead.get(5, TimeUnit.SECONDS);
        loader.join(5_000);

        owned.tierAdded("btcl_res_9");
        owned.tierAdded("btcl_res_5");
        assertThat(loads).as("once; a tier it was built over is nothing").containsExactly("btcl_res_9");
        owned.close();
    }

    @Test
    void twoWordsAtOnce_loadTheTierOnce() throws Exception {
        MemLedger memLedger = lab.start();
        MemLedgerPort port = (MemLedgerPort) SwitchLedgers.memLedger(memLedger, SwitchLedgerSettings.standard(), Clock.systemUTC());
        List<String> loads = new CopyOnWriteArrayList<>();
        CountDownLatch loading = new CountDownLatch(1), letGo = new CountDownLatch(1);
        OwnedMemLedgerPort owned = new OwnedMemLedgerPort(port, memLedger, Set.of("btcl"), tier -> {
            loads.add(tier);
            loading.countDown();
            awaitQuietly(letGo);
            return true;
        });
        Thread first = new Thread(() -> owned.tierAdded("btcl_res_9"));
        first.start();
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        Thread second = new Thread(() -> owned.tierAdded("btcl_res_9"));         // the same word while the first loads: it waits at the gate
        second.start();
        Thread.sleep(100);
        letGo.countDown();
        first.join(5_000);
        second.join(5_000);
        assertThat(loads).as("the second word finds the tier loaded once it passes the gate").containsExactly("btcl_res_9");
        owned.close();
    }

    @Test
    void aLoadThatFails_isSaid_andALaterWordTriesAgain() {
        MemLedger memLedger = lab.start();
        MemLedgerPort port = (MemLedgerPort) SwitchLedgers.memLedger(memLedger, SwitchLedgerSettings.standard(), Clock.systemUTC());
        List<String> loads = new CopyOnWriteArrayList<>();
        OwnedMemLedgerPort owned = new OwnedMemLedgerPort(port, memLedger, Set.of("btcl"), tier -> { loads.add(tier); throw new IllegalStateException("scripted"); });
        owned.tierAdded("btcl_res_9");
        owned.tierAdded("btcl_res_9");
        assertThat(loads).containsExactly("btcl_res_9", "btcl_res_9");
        assertThat(owned.tierLoaded("btcl_res_9")).as("a load that was said failed: the gate hands it again").isFalse();
        assertThat(owned.tierLoaded("btcl")).as("built over at the start").isTrue();
        owned.close();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
