package com.telcobright.seed.prepaid.admin;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.idgen.testkit.InMemoryIdSource;
import com.telcobright.seed.prepaid.api.AccountRule;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.prepaid.api.PrepaidAdmin;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.ManualClock;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2b item 4 · the money roads over the REAL switch ledger as a ledger DB ("package": accounts, reserves cache-only, references, purchases):
 * L2c · a purchase makes its accounts — ONE record, ids from the application's source, the database rows under those very ids (the
 * ledger DB names rtc-domain's {@code @GeneratedValue} account key as minted by the caller); the partner's accounts and their expiry come
 * from the ledger's purchases (S4), and an expired purchase's account is skipped by the order. The engines' scenario (H2, MySQL,
 * PostgreSQL: a restart, a hold across it, a repeat) is {@code PurchaseMakesItsAccountsScenario}'s.
 */
class PurchaseThroughTheLedgerTest {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final int CLIENT = 701;

    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-27T00:00:00Z"), DHAKA);
    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("res_2").account("btcl", 21, 5, "BDT", "50").account("res_2", 11, 1, "TF_min", "100")
        .account("res_2", 12, 2, "BDT", "40").chargeReferences().purchaseTables()
        .purchase("btcl", 5, 2, "2030-01-01 00:00:00").purchase("res_2", 1, CLIENT, "2026-01-01 00:00:00").purchase("res_2", 2, CLIENT, "2030-01-01 00:00:00")
        .asLedgerDb(List.of("package"), List.of("packageaccountreserve"), 5_000);
    private final InMemoryIdSource ids = new InMemoryIdSource();
    private RoutesphereKit kit;
    private PrepaidAdmin admin;

    @AfterEach
    void close() { lab.close(); }

    private void start() {
        MemLedger memLedger = lab.start();
        Set<String> configured = com.telcobright.seed.switchledger.LedgerLab.EVERY_ENTITY;   // the word "package": every table, the catalog too (C4)
        LedgerPort port = SwitchLedgers.over(memLedger, SwitchLedgerSettings.standard(), clock, configured).port();
        kit = RoutesphereKit.builder().tenants(TenantLookup.of(tree())).ledger(port).cdr(new com.telcobright.seed.sessionflow.testkit.RecordingCdrSink()).zone(DHAKA).clock(clock).build();
        admin = Prepaid.<com.telcobright.seed.sessionflow.api.RoutesphereContext>ofTheLanesAnswers(kit).admin().withIds(ids);
    }

    /**
     * L2c · The production account (rtc-domain {@code PackageAccount.id} is {@code @GeneratedValue}): the purchase is ONE record, its accounts
     * carry the ids the application's source minted above every account held, and the database rows carry those very ids.
     */
    @Test
    void aPurchaseMakesItsAccounts_underTheMintedIds_inTheCacheAndTheDatabase() {
        start();
        long before = lab.walRecords();

        LedgerPort.Purchased bought = admin.purchase("btcl", "res_2", CLIENT, offer(30), "pay-1");

        assertThat(lab.walRecords()).as("ONE record").isEqualTo(before + 1);
        assertThat(bought.accounts()).as("two accounts above every account held (21)").hasSize(2).allMatch(id -> id > 21);
        for (long id : bought.accounts()) LedgerLab.await("account " + id + " in the database", () -> lab.dbBalance("res_2", id) != null);
        assertThat(lab.dbBalance("res_2", bought.accounts().get(1))).as("the minutes, under the cache's id").isEqualByComparingTo("300");
        assertThat(lab.dbPurchase("res_2", bought.accounts().get(0))).isEqualByComparingTo(String.valueOf(bought.purchaseId()));
        assertThat(admin.accounts("btcl", "res_2", CLIENT)).extracting(PrepaidAdmin.Account::id)
            .as("prime-context's order (S6): by expiry — 11 (2026-01-01), the new purchase's (30 days), 12 (2030)")
            .containsExactly(11L, bought.accounts().get(0), bought.accounts().get(1), 12L);
    }

    /** S4 · the partner's accounts as the LEDGER names them (its cached purchases), each with its purchase's expiry and its live balance. */
    @Test
    void aPartnersAccounts_comeFromTheLedgersPurchases_withTheirExpiry() {
        start();
        lab.purchase("res_2", 3, 702, "2030-01-01 00:00:00");                                  // another partner's purchase: not listed

        assertThat(admin.accounts("btcl", "res_2", CLIENT)).extracting(PrepaidAdmin.Account::id).containsExactly(11L, 12L);
        assertThat(admin.accounts("btcl", "res_2", CLIENT)).extracting(PrepaidAdmin.Account::expiresAt)
            .containsExactly(LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2030, 1, 1, 0, 0));
        assertThat(admin.accounts("btcl", "res_2", CLIENT)).extracting(a -> a.balance().stripTrailingZeros().toPlainString()).containsExactly("100", "40");
    }

    /** L3 · the partner's purchases as the LEDGER caches them (the officer's list), oldest first; a purchase made through it is listed at once. */
    @Test
    void aPartnersPurchases_comeFromTheLedgersCache_oldestFirst_aNewOneAtOnce() {
        start();
        lab.purchase("res_2", 3, 702, "2030-01-01 00:00:00");                                  // another partner's purchase: not listed

        assertThat(admin.purchases("btcl", "res_2", CLIENT)).extracting(LedgerPort.Purchase::id).containsExactly(1L, 2L);
        assertThat(admin.purchases("btcl", "res_2", CLIENT)).extracting(LedgerPort.Purchase::expires)
            .containsExactly(LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2030, 1, 1, 0, 0));

        LedgerPort.Purchased bought = admin.purchase("btcl", "res_2", CLIENT, offer(30), "pay-2");

        assertThat(admin.purchases("btcl", "res_2", CLIENT)).last().satisfies(p -> {
            assertThat(p.id()).isEqualTo(bought.purchaseId());
            assertThat(p.packageId()).isEqualTo(7L);
            assertThat(p.at()).isEqualTo(now());
            assertThat(p.expires()).isEqualTo(now().plusDays(30));
            assertThat(p.price()).isEqualByComparingTo("99.00");
        });
    }

    /**
     * S6 · The ledger's catalog is prime-context's: the accounts of ACTIVE purchases only (a cancelled one never pays, never listed), in
     * prime-context's order — the operator's priority first, then the soonest expiry, then the older purchase.
     */
    @Test
    void theLedgersCatalog_isPrimeContexts_activePurchasesOnly_theOperatorsPriorityThenTheSoonestExpiry() {
        lab.purchase("res_2", 3, CLIENT, "2026-02-01 00:00:00", "2028-01-01 00:00:00", "CANCELLED", null)
            .purchase("res_2", 4, CLIENT, "2026-03-01 00:00:00", "2031-01-01 00:00:00", "ACTIVE", 1)              // the operator's choice
            .purchase("res_2", 5, CLIENT, "2026-04-01 00:00:00", "2029-01-01 00:00:00", "active", null)
            .account("res_2", 31, 3, "TF_min", "7").account("res_2", 41, 4, "TF_min", "8").account("res_2", 51, 5, "TF_min", "9");
        start();

        assertThat(admin.accounts("btcl", "res_2", CLIENT)).extracting(PrepaidAdmin.Account::id)
            .as("41 (the operator's priority), then by expiry 11 (2026, expired: listed, never paid), 51 (2029), 12 (2030); 31 cancelled: never")
            .containsExactly(41L, 11L, 51L, 12L);
    }

    @Test
    void anExpiredPurchasesAccount_isSkippedByTheOrder_theExpiryFromTheLedger() {
        start();
        LevelAdmission buyer = new LevelAdmission(0, kit.tenants().tenantByDbName("btcl", "res_2").orElseThrow(), partner(CLIENT), null);

        List<PackageAccount> fromTheLedger = kit.ledger().accountsOf(buyer).orElseThrow();
        List<PackageAccount> fundable = AccountRule.UNITS_THEN_CASH.fundable(fromTheLedger, now());

        assertThat(fromTheLedger).extracting(PackageAccount::getId).containsExactly(11L, 12L);
        assertThat(fundable).as("11's purchase expired on 2026-01-01: only the cash pays").extracting(PackageAccount::getId).containsExactly(12L);
    }

    private LedgerPort.Offer offer(int days) {
        return new LedgerPort.Offer(7, "Combo", new BigDecimal("99.00"), now(), now().plusDays(days),
            List.of(new LedgerPort.Bucket("BDT", new BigDecimal("60")), new LedgerPort.Bucket("TF_min", new BigDecimal("300"))));
    }

    private LocalDateTime now() { return LocalDateTime.now(clock).withNano(0); }

    private static Partner partner(int id) {
        Partner p = new Partner();
        p.setIdPartner(id);
        return p;
    }

    private static Tenant tree() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(2, "res_2", PartnerType.RESELLER);
        b.tenant("res_2", "btcl").partner(CLIENT, "Client", PartnerType.CUSTOMER);
        return b.build();
    }
}
