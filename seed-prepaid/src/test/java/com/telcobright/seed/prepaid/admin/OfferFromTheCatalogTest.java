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
import com.telcobright.seed.switchledger.api.LedgerAccount;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §15 · {@code PrepaidAdmin.offerOf}: the offer of a CATALOG package from the cache — one bucket per item, the item NAMED — and a purchase of
 * it opens each account with its item ({@code id_packageaccount.id_packageitem}), so the walk relates the account to its product by key;
 * refused in words for an unknown package, one not for sale, one without items. Mutation seen red: the bucket's item dropped on the way
 * to the account.
 */
class OfferFromTheCatalogTest {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final int CLIENT = 701;
    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-29T00:00:00Z"), DHAKA);
    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("res_2").chargeReferences().purchaseTables()
        .ddl("INSERT INTO res_2.package (id, name, basePrice, vatPercent, createDate, validity, aitPercent, activeStatus) VALUES (11, 'Video and image combo', 250, 0, TIMESTAMP '2026-01-01 00:00:00', 2592000, 0, TRUE)")
        .ddl("INSERT INTO res_2.package (id, name, basePrice, vatPercent, createDate, validity, aitPercent, activeStatus) VALUES (12, 'Retired', 1, 0, TIMESTAMP '2026-01-01 00:00:00', 2592000, 0, FALSE)")
        .item("res_2", 111, 11, "1001", "AD_view", "100").item("res_2", 112, 11, "1003", "AD_view", "500")
        .asLedgerDb(List.of("package"), List.of("packageaccountreserve"), 5_000);
    private PrepaidAdmin admin;

    @BeforeEach
    void start() {
        MemLedger memLedger = lab.start();
        LedgerPort port = SwitchLedgers.over(memLedger, SwitchLedgerSettings.standard(), clock, LedgerLab.EVERY_ENTITY).port();
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(tree())).ledger(port).cdr(new RecordingCdrSink()).zone(DHAKA).clock(clock).build();
        admin = Prepaid.<RoutesphereContext>ofTheLanesAnswers(kit).admin().withIds(new InMemoryIdSource());
    }

    @AfterEach
    void close() { lab.close(); }

    @Test
    void theOfferOfACatalogPackage_oneBucketPerItemNamed_aPurchaseOpensEachAccountWithItsItem() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 29, 12, 0);
        LedgerPort.Offer offer = admin.offerOf("btcl", "res_2", 11, at);

        assertThat(offer.name()).isEqualTo("Video and image combo");
        assertThat(offer.price()).isEqualByComparingTo("250");
        assertThat(offer.expires()).isEqualTo(at.plusSeconds(2592000));
        assertThat(offer.buckets()).extracting(LedgerPort.Bucket::uom, LedgerPort.Bucket::opening, LedgerPort.Bucket::itemId)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("AD_view", new BigDecimal("100.00"), 111L), org.assertj.core.groups.Tuple.tuple("AD_view", new BigDecimal("500.00"), 112L));

        LedgerPort.Purchased bought = admin.purchase("btcl", "res_2", CLIENT, offer, "pay-2026-10-29-1");
        assertThat(bought.accounts()).hasSize(2);
        List<LedgerAccount> accounts = bought.accounts().stream().map(id -> lab.ledger().<Long, LedgerAccount>getEntity("res_2", "PackageAccount", id)).toList();
        assertThat(accounts).extracting(LedgerAccount::getIdPackageItem).as("mutation: the bucket's item dropped → null → red").containsExactly(111L, 112L);
        assertThat(accounts).extracting(LedgerAccount::getBalanceAfter).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class).containsExactly(new BigDecimal("100"), new BigDecimal("500"));
        LedgerLab.await("the write-behind writes the item", () -> lab.dbDecimal("SELECT id_packageitem FROM res_2.packageaccount WHERE id_packageaccount = " + bought.accounts().get(0)) != null);
        assertThat(lab.dbDecimal("SELECT id_packageitem FROM res_2.packageaccount WHERE id_packageaccount = " + bought.accounts().get(1))).isEqualByComparingTo("112");
    }

    @Test
    void refusedInWords_anUnknownPackage_oneNotForSale_oneWithoutItems() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 29, 12, 0);
        assertThatThrownBy(() -> admin.offerOf("btcl", "res_2", 404, at)).hasMessageContaining("package 404 is not in the catalog");
        assertThatThrownBy(() -> admin.offerOf("btcl", "res_2", 12, at)).hasMessageContaining("not for sale");
        assertThatThrownBy(() -> admin.offerOf("btcl", "res_2", 3, at)).hasMessageContaining("has no items");
    }

    private static Tenant tree() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(2, "res_2", PartnerType.RESELLER);
        b.tenant("res_2", "btcl").partner(CLIENT, "Client", PartnerType.CUSTOMER);
        return b.build();
    }
}
