package com.telcobright.seed.switchledger;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.switchledger.api.LedgerDb;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.AddTierLive;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.seed.switchledger.internal.OwnedMemLedgerPort;
import com.telcobright.seed.switchledger.internal.TwoEntities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2b item 3 · the ledger DB, a seed feature: its entities by name or table prefix ({@code package}), the reserve cache-only, every tier of
 * the tree; the doorbell's words decide — an INSERT on a cached table is ONE reload of new rows, coalesced (50 rings in a second = one or two
 * reloads); its own tables' UPDATE/DELETE, a table it does not cache, a cache-only table and a tree's reload alone are nothing; a catch-up
 * word is the reload; a tier the ring names that the ledger lacks — or {@code tenant_created}'s — is loaded, its rows served with no restart.
 */
class LedgerDbTest {

    private static final long SPACING_MS = 1_000;

    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("res_9").account("btcl", 1, 10, "BDT", "10").account("res_9", 2, 20, "BDT", "20")
        .chargeReferences().purchaseTables().asLedgerDb(List.of("package"), List.of("packageaccountreserve"), SPACING_MS);
    private OwnedMemLedgerPort owned;

    @AfterEach
    void close() {
        if (owned != null) owned.close();
        lab.close();
    }

    private OwnedMemLedgerPort start() {
        MemLedger memLedger = lab.start();
        Set<String> configured = Set.of("PackageAccount", "PackageAccountReserve", "PackageAccountCharge", "PackagePurchase");
        MemLedgerPort port = (MemLedgerPort) SwitchLedgers.over(memLedger, SwitchLedgerSettings.standard(), Clock.systemUTC(), configured).port();
        owned = new OwnedMemLedgerPort(port, memLedger, Set.of("btcl", "res_9"), AddTierLive.of(memLedger)::load, SPACING_MS, LedgerDb.of(lab.ledgerDbSettings()));
        return owned;
    }

    @Test
    void anInsertOnACachedTable_isOneReload_andTheNewAccountPays() {
        start();
        lab.account("res_9", 901, 90, "BDT", "5");                                       // a purchase wrote an account behind the ledger

        owned.doorbell("change", List.of("res_9.packageaccount:INSERT"));

        assertThat(owned.mergesRun()).isEqualTo(1);
        assertThat(owned.balanceOf(level("res_9", 901))).as("served without a restart").hasValueSatisfying(b -> assertThat(b).isEqualByComparingTo("5"));
    }

    @Test
    void fiftyRingsInOneSecond_areOneOrTwoReloads() throws Exception {
        start();
        long until = System.currentTimeMillis() + 1_000;
        for (int i = 0; i < 50; i++) owned.doorbell("change", List.of("btcl.packageaccount:INSERT"));
        assertThat(System.currentTimeMillis()).as("the fifty rang within a second").isLessThan(until);
        Thread.sleep(SPACING_MS + 700);

        assertThat(owned.mergesRun()).isBetween(1L, 2L);
    }

    @Test
    void itsOwnTablesUpdatesAndDeletes_aTableItDoesNotCache_aCacheOnlyTable_andATreeReload_areNothing() throws Exception {
        start();
        owned.doorbell("change", List.of("btcl.packageaccountreserve:UPDATE", "btcl.packageaccount:UPDATE", "res_9.packageaccount:DELETE",
            "btcl.partner:INSERT", "btcl.packageaccountreserve:INSERT"));
        owned.treeReloaded();
        Thread.sleep(SPACING_MS + 300);

        assertThat(owned.mergesRun()).as("nothing re-read (S2)").isZero();
    }

    @Test
    void theCatchUpWords_reload() throws Exception {
        start();
        owned.doorbell("start", List.of());
        assertThat(owned.mergesRun()).isEqualTo(1);
        Thread.sleep(SPACING_MS + 100);
        owned.doorbell("change", List.of("*:SNAPSHOT"));
        assertThat(owned.mergesRun()).isEqualTo(2);
        Thread.sleep(SPACING_MS + 100);
        owned.doorbell("stream-open", List.of());
        assertThat(owned.mergesRun()).isEqualTo(3);
    }

    @Test
    void aTierTheLedgerLacks_isLoadedByTheRing_orByTenantCreated_itsRowsServedWithoutARestart() {
        start();
        lab.schema("res_12").account("res_12", 1201, 120, "BDT", "7").schema("res_13").account("res_13", 1301, 130, "BDT", "8");
        lab.ledger();

        owned.doorbell("provision", List.of("res_12.packageaccount:INSERT"));                // a lost tenant_created: the ring names the tier
        owned.tierAdded("res_13");                                                          // tenant_created

        assertThat(owned.tierLoaded("res_12")).isTrue();
        assertThat(owned.balanceOf(level("res_12", 1201))).hasValueSatisfying(b -> assertThat(b).isEqualByComparingTo("7"));
        assertThat(owned.balanceOf(level("res_13", 1301))).hasValueSatisfying(b -> assertThat(b).isEqualByComparingTo("8"));
    }

    @Test
    void theLedgerHoldsExactlyTheConfiguredEntities_aWordThatNamesNothingIsRefused() {
        MemLedger memLedger = lab.start();
        assertThat(TwoEntities.check(memLedger, Set.of("PackageAccount", "PackageAccountReserve", "PackageAccountCharge", "PackagePurchase"))).hasSize(4);
        assertThatThrownBy(() -> TwoEntities.check(memLedger, Set.of("PackageAccount", "PackageAccountReserve")))
            .hasMessageContaining("caches exactly its configured entities");
        assertThatThrownBy(() -> LedgerDb.of(new com.telcobright.seed.switchledger.dependencies.LedgerDbSettings(List.of("package", "partner"), List.of(),
            java.nio.file.Path.of("w"), java.nio.file.Path.of("q"), 1_000))).hasMessageContaining("'partner' names no known entity or table");
    }

    private static LevelAdmission level(String tier, long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(1);
        PackageAccount account = new PackageAccount();
        account.setId(accountId);
        return new LevelAdmission(0, new Tenant(tier), partner, account);
    }
}
