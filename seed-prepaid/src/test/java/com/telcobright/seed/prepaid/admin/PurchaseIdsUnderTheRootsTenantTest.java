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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ARCH-0100 · the ids of a purchase are minted under THE ROOT'S TENANT — the root word of the call, whatever it is — never the tier's
 * word and never a constant: a root named {@code acme} with a reseller tier {@code res_7} buys at the tier, and every id reached the
 * id source under tenant {@code acme} alone (the id service keeps one counter per (tenant, entity); the client has no default tenant).
 */
class PurchaseIdsUnderTheRootsTenantTest {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final int CLIENT = 901;

    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-30T18:00:00Z"), DHAKA);
    private final LedgerLab lab = LedgerLab.open().schema("acme").schema("res_7").account("res_7", 31, 1, "BDT", "5")
        .chargeReferences().purchaseTables().purchase("res_7", 1, CLIENT, "2030-01-01 00:00:00")
        .asLedgerDb(List.of("package"), List.of("packageaccountreserve"), 5_000);
    private final InMemoryIdSource ids = new InMemoryIdSource();

    @AfterEach
    void close() { lab.close(); }

    @Test
    void everyIdOfAPurchase_isMintedUnderTheRootsTenant_neverTheTiers_neverAConstant() {
        MemLedger memLedger = lab.start();
        LedgerPort port = SwitchLedgers.over(memLedger, SwitchLedgerSettings.standard(), clock, LedgerLab.EVERY_ENTITY).port();
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(tree())).ledger(port).cdr(new RecordingCdrSink()).zone(DHAKA).clock(clock).build();
        PrepaidAdmin admin = Prepaid.<RoutesphereContext>ofTheLanesAnswers(kit).admin().withIds(ids);

        LedgerPort.Purchased bought = admin.purchase("acme", "res_7", CLIENT, offer(), "acme-pay-1");

        assertThat(bought.accounts()).hasSize(2);
        assertThat(ids.tenants()).as("the root's word, acme — not res_7, not btcl").containsExactly("acme");
        assertThat(ids.entitiesOf("acme")).as("the purchase's table and the account's table, under acme").hasSize(2);
        assertThat(ids.entitiesOf("res_7")).isEmpty();
        assertThat(ids.entitiesOf("btcl")).isEmpty();
    }

    private LedgerPort.Offer offer() {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        return new LedgerPort.Offer(7, "Combo", new BigDecimal("99.00"), now, now.plusDays(30),
            List.of(new LedgerPort.Bucket("BDT", new BigDecimal("60")), new LedgerPort.Bucket("TF_min", new BigDecimal("300"))));
    }

    private static Tenant tree() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("acme").partner(2, "res_7", PartnerType.RESELLER);
        b.tenant("res_7", "acme").partner(CLIENT, "Client", PartnerType.CUSTOMER);
        return b.build();
    }
}
