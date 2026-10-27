package com.telcobright.seed.prepaid.admin;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.idgen.testkit.InMemoryIdSource;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.prepaid.api.PrepaidAdmin;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.ManualClock;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2b item 5 · the money roads as plain Java on {@link PrepaidAdmin} (the application mounts its REST on them; the seed has no server, R0):
 * purchase · credit (+ reparent) · accounts · balance, over the REAL switch ledger as a ledger DB — each works through the ledger, and each
 * refuses in words (a tier not in the root's tree, an account the tier's ledger does not hold, a credit of nothing, a purchase without its
 * id source, its buckets, or its reference). (L2c: the refusal "while the database makes the account ids" is gone — the ledger DB names the
 * account's key as minted by the caller, and a purchase makes its accounts: {@code PurchaseThroughTheLedgerTest}.)
 */
class MoneyRoadsTest {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final int CLIENT = 701;

    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-27T00:00:00Z"), DHAKA);
    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("res_2").account("res_2", 11, 1, "TF_min", "100").account("res_2", 12, 2, "BDT", "40")
        .chargeReferences().purchaseTables().purchase("res_2", 1, CLIENT, "2026-01-01 00:00:00").purchase("res_2", 2, CLIENT, "2030-01-01 00:00:00")
        .asLedgerDb(List.of("package"), List.of("packageaccountreserve"), 5_000);
    private PrepaidAdmin admin;

    @BeforeEach
    void start() {
        MemLedger memLedger = lab.start();
        LedgerPort port = SwitchLedgers.over(memLedger, SwitchLedgerSettings.standard(), clock,
            Set.of("PackageAccount", "PackageAccountReserve", "PackageAccountCharge", "PackagePurchase")).port();
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(tree())).ledger(port).cdr(new RecordingCdrSink()).zone(DHAKA).clock(clock).build();
        admin = Prepaid.<RoutesphereContext>ofTheLanesAnswers(kit).admin();
    }

    @AfterEach
    void close() { lab.close(); }

    @Test
    void creditReparentAccountsAndBalance_workThroughTheLedger() {
        assertThat(admin.credit("btcl", "res_2", 12, new BigDecimal("10"), "topup-1").after()).isEqualByComparingTo("50");
        assertThat(admin.balance("btcl", "res_2", 12)).isEqualByComparingTo("50");

        admin.reparent("btcl", "res_2", 12, 1);                                              // onto the expired purchase

        assertThat(admin.accounts("btcl", "res_2", CLIENT)).extracting(PrepaidAdmin.Account::expiresAt)
            .as("the account's expiry follows its purchase").containsExactly(LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(admin.balance("btcl", "res_2", 12)).as("its balance kept").isEqualByComparingTo("50");
    }

    @Test
    void everyRoadRefusesInWords() {
        LedgerPort.Offer offer = new LedgerPort.Offer(7, "Combo", new BigDecimal("9"), now(), now().plusDays(30), List.of(new LedgerPort.Bucket("BDT", BigDecimal.TEN)));
        PrepaidAdmin withIds = admin.withIds(new InMemoryIdSource());

        assertThatThrownBy(() -> admin.purchase("btcl", "res_2", CLIENT, offer, "p")).hasMessageContaining("the application passes its seed-idgen IdSource");
        assertThatThrownBy(() -> withIds.purchase("btcl", "res_9", CLIENT, offer, "p")).hasMessageContaining("the tier 'res_9' is not in the tree of the root 'btcl'");
        assertThatThrownBy(() -> withIds.purchase("btcl", "res_2", CLIENT, new LedgerPort.Offer(7, "none", BigDecimal.ONE, now(), now().plusDays(1), List.of()), "p"))
            .hasMessageContaining("at least one bucket");
        assertThatThrownBy(() -> withIds.purchase("btcl", "res_2", CLIENT, offer, " ")).hasMessageContaining("a purchase carries its reference");
        assertThatThrownBy(() -> admin.credit("btcl", "res_2", 12, BigDecimal.ZERO, "z")).hasMessageContaining("a credit is positive");
        assertThatThrownBy(() -> admin.credit("btcl", "res_2", 999, BigDecimal.ONE, "z")).hasMessageContaining("account 999 is not held in schema res_2");
        assertThatThrownBy(() -> admin.reparent("btcl", "res_2", 999, 1)).hasMessageContaining("account 999 is not held in schema res_2");
        assertThatThrownBy(() -> admin.accounts("btcl", "res_9", CLIENT)).hasMessageContaining("the tier 'res_9' is not in the tree of the root 'btcl'");
        assertThatThrownBy(() -> admin.balance("btcl", "res_2", 999)).hasMessageContaining("account 999 is not held by the ledger of the tier 'res_2'");
        assertThat(admin.balance("btcl", "res_2", 12)).as("nothing moved").isEqualByComparingTo("40");
    }

    private LocalDateTime now() { return LocalDateTime.now(clock).withNano(0); }

    private static Tenant tree() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(2, "res_2", PartnerType.RESELLER);
        b.tenant("res_2", "btcl").partner(CLIENT, "Client", PartnerType.CUSTOMER);
        return b.build();
    }
}
