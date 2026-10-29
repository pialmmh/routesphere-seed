package com.telcobright.seed.prepaid.admin;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.prepaid.api.PrepaidAdmin;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Product;
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

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §15.3 · The portal's product CRUD through {@code PrepaidAdmin}: create, update, deactivate — each ONE cached-ledger unit, the cache
 * answering at once, the database following; refused in words (a key taken, a kind outside the enum, a group the tier does not know, a
 * change of the key or the source, a tier the root lacks). Mutation seen red: the refusal of the ledger not thrown.
 */
class ProductCrudThroughTheLedgerTest {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    private static final Set<String> HELD = Set.of("PackageAccount", "PackageAccountReserve", "PackageAccountCharge", "PackagePurchase", "Package", "PackageItem", "Product", "ServiceGroup");
    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-30T00:00:00Z"), DHAKA);
    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("res_2").chargeReferences().purchaseTables().productTable().serviceGroupTable()
        .product("res_2", "1001", 1001, "Video ad 30 s", "ad", "video", 30, "view", 30)
        .asLedgerDb(List.of("package", "product", "servicegroup"), List.of("packageaccountreserve"), 5_000);
    private PrepaidAdmin admin;

    @BeforeEach
    void start() {
        MemLedger memLedger = lab.start();
        LedgerPort port = SwitchLedgers.over(memLedger, SwitchLedgerSettings.standard(), clock, HELD).port();
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(tree())).ledger(port).cdr(new RecordingCdrSink()).zone(DHAKA).clock(clock).build();
        admin = Prepaid.<RoutesphereContext>ofTheLanesAnswers(kit).admin();
    }

    @AfterEach
    void close() { lab.close(); }

    private static Product portal(String key, String kind, String media, Integer seconds, int group) {
        return new Product(key, null, null, "Product " + key, kind, media, seconds, "view", true, null, group);
    }

    @Test
    void createUpdateDeactivate_eachOneUnit_theCacheAnswersAtOnce_theDatabaseFollows() {
        long records = lab.walRecords();

        Product made = admin.createProduct("btcl", "res_2", portal("5001", "ad", "video", 45, 30), "officer-7:product");
        assertThat(made).extracting(Product::key, Product::kind, Product::media, Product::seconds, Product::source, Product::serviceGroupId, Product::active).containsExactly("5001", "ad", "video", 45, "portal", 30, true);
        assertThat(admin.products("btcl", "res_2")).extracting(Product::key).containsExactly("1001", "5001");
        assertThat(admin.serviceGroups("btcl", "res_2")).containsEntry(30, "AdView");

        Product changed = admin.updateProduct("btcl", "res_2", "5001", Map.of("seconds", 50, "name", "Video ad 50 s"), "officer-7:edit");
        assertThat(changed).extracting(Product::seconds, Product::name).containsExactly(50, "Video ad 50 s");

        Product off = admin.deactivateProduct("btcl", "res_2", "5001", "officer-7:off");
        assertThat(off.active()).isFalse();
        assertThat(lab.walRecords()).as("three writes, three records").isEqualTo(records + 3);
        LedgerLab.await("the write-behind", () -> lab.dbDecimal("SELECT seconds FROM res_2.product_catalog WHERE product_key = '5001'") != null
            && lab.dbDecimal("SELECT seconds FROM res_2.product_catalog WHERE product_key = '5001'").intValue() == 50);
    }

    @Test
    void refusedInWords() {
        assertThatThrownBy(() -> admin.createProduct("btcl", "res_2", portal("1001", "ad", "video", 30, 30), "r1")).hasMessageContaining("product 1001 is in the catalog of res_2 already");
        assertThatThrownBy(() -> admin.createProduct("btcl", "res_2", portal("5002", "snack", null, null, 30), "r2")).as("mutation: the ledger's refusal not thrown → red").hasMessageContaining("kind 'snack' is not one of");
        assertThatThrownBy(() -> admin.createProduct("btcl", "res_2", portal("5003", "ad", "video", 30, 77), "r3")).hasMessageContaining("service group 77 is not one res_2 knows");
        assertThatThrownBy(() -> admin.createProduct("btcl", "res_2", portal(" ", "ad", "video", 30, 30), "r4")).hasMessageContaining("carries its key");
        assertThatThrownBy(() -> admin.createProduct("btcl", "res_2", portal("5004", "ad", "video", 30, 30), " ")).hasMessageContaining("carries its reference");
        assertThatThrownBy(() -> admin.updateProduct("btcl", "res_2", "1001", Map.of("productKey", "9"), "r5")).hasMessageContaining("never change");
        assertThatThrownBy(() -> admin.updateProduct("btcl", "res_2", "4040", Map.of("name", "x"), "r6")).hasMessageContaining("product 4040 is not in the catalog of res_2");
        assertThatThrownBy(() -> admin.createProduct("btcl", "res_9", portal("5005", "ad", "video", 30, 30), "r7")).isInstanceOf(RuntimeException.class);
        assertThat(admin.products("btcl", "res_2")).extracting(Product::key).as("nothing written").containsExactly("1001");
    }

    private static Tenant tree() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(2, "res_2", PartnerType.RESELLER);
        b.tenant("res_2", "btcl").partner(701, "Client", PartnerType.CUSTOMER);
        return b.build();
    }
}
