package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.ItemCatalog;
import com.telcobright.seed.switchledger.api.SwitchLedger;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §15 (the owner, 2026-10-29: "map prefix = product id, then cache the product with all necessary entities in the mem-ledger based cache
 * … there can't be any db lookup in the hot path") · the tier's catalog BY KEY from the cache: a package's items by their product key
 * ({@code packageitem.prefix}), an item by its id, the package a purchase bought, the products — every answer a map get on an immutable
 * snapshot; a ledger that caches no catalog answers empty; the snapshot is made again when new rows were merged. Mutations seen red: the
 * item keyed by its id instead of its prefix; {@code forget()} dropped; the products left out of the snapshot.
 */
class CatalogByKeyTest {

    private static final Set<String> HELD = Set.of(Books.ACCOUNT, Books.RESERVE, Books.PURCHASE, Books.PACKAGE, Books.PACKAGE_ITEM, Books.PRODUCT, Books.SERVICE_GROUP);

    private final LedgerLab lab = LedgerLab.open().schema("res_2").purchaseTables().productTable().serviceGroupTable()
        .ddl("INSERT INTO res_2.package (id, name, basePrice, vatPercent, createDate, validity, aitPercent, activeStatus) VALUES (11, 'Video and image combo', 250, 0, TIMESTAMP '2026-01-01 00:00:00', 2592000, 0, TRUE)")
        .item("res_2", 111, 11, "1001", "AD_view", "100").item("res_2", 112, 11, "1003", "AD_view", "500").item("res_2", 113, 11, " 1001 ", "AD_view", "7")   // a repeated key: the lower id keeps it
        .item("res_2", 21, 2, "880", "TF_min", "300").item("res_2", 22, 2, "8801", "TF_min", "100")
        .product("res_2", "1001", 1001, "Video ad 30 s", "Ad", "Video", 30, "view", 30).product("res_2", "1003", 1003, "Image ad", "ad", "image", null, "view", 30)
        .purchase("res_2", 5, 705, "2030-01-01 00:00:00").ddl("UPDATE res_2.packagepurchase SET id_package = 11 WHERE id = 5")
        .accountOfItem("res_2", 51, 5, 111, "AD_view", "100")
        .asLedgerDb(List.of("packageaccount", "packageaccountreserve", "packagepurchase", "catalog", "product", "servicegroup"), List.of("packageaccountreserve"), 5_000);

    @AfterEach
    void close() { lab.close(); }

    private static LevelAdmission tier(String db) {
        Tenant t = new Tenant();
        t.setDbName(db);
        return new LevelAdmission(0, t, null, null);
    }

    @Test
    void theCatalogByKey_itemsByTheirPrefix_productsByKey_thePackageOfAPurchase_allFromTheCache() {
        lab.start();
        SwitchLedger ledger = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC(), HELD);
        ItemCatalog catalog = ledger.port().catalogOf(tier("res_2")).orElseThrow();

        assertThat(catalog.packageOf(11)).get().extracting(LedgerPort.CatalogPackage::name, LedgerPort.CatalogPackage::price, LedgerPort.CatalogPackage::validitySeconds, LedgerPort.CatalogPackage::active)
            .containsExactly("Video and image combo", new BigDecimal("250.00"), 2592000L, true);
        assertThat(catalog.itemsOf(11).keySet()).as("the hashtable of step 2: by the PREFIX (trimmed), not the id — mutation: keyed by id → red").containsExactlyInAnyOrder("1001", "1003");
        assertThat(catalog.itemsOf(11).get("1001")).extracting(LedgerPort.Item::id, LedgerPort.Item::uom, LedgerPort.Item::quantity).containsExactly(111L, "AD_view", new BigDecimal("100.00"));
        assertThat(catalog.itemsOf(2).keySet()).as("the call's items by prefix").containsExactlyInAnyOrder("880", "8801");
        assertThat(catalog.itemsOf(404)).isEmpty();
        assertThat(catalog.itemOf(112)).get().extracting(LedgerPort.Item::key).isEqualTo("1003");
        assertThat(catalog.packageOfPurchase(5)).contains(11L);
        assertThat(catalog.packageOfPurchase(6)).isEmpty();
        assertThat(catalog.products().get("1001")).extracting(LedgerPort.Product::kind, LedgerPort.Product::media, LedgerPort.Product::seconds, LedgerPort.Product::uom, LedgerPort.Product::active, LedgerPort.Product::source, LedgerPort.Product::serviceGroupId, LedgerPort.Product::id)
            .as("kind, media and source are case-blind; the seconds, the group and the Odoo id kept").containsExactly("ad", "video", 30, "view", true, "odoo", 30, 1001L);
        assertThat(catalog.products().get("1003").seconds()).isNull();
        assertThat(catalog.productOf(" 1001 ")).isPresent();
        assertThat(catalog.itemsOf(11).get("1001")).extracting(LedgerPort.Item::serviceGroupId, LedgerPort.Item::kind).as("§15.3: the item carries its product's group and kind, resolved at load — mutation: items made before products → null → red").containsExactly(30, "ad");
        assertThat(catalog.itemsOf(2).get("880")).extracting(LedgerPort.Item::serviceGroupId, LedgerPort.Item::kind).as("an item whose key names no product: no group, no kind").containsExactly(null, null);
        assertThat(catalog.serviceGroups()).containsEntry(30, "AdView").containsEntry(101, "WiFi").containsEntry(1, "DomesticICX");
        assertThat(ledger.port().catalogOf(tier("res_2")).orElseThrow()).as("the same snapshot until new rows are merged").isSameAs(catalog);
    }

    @Test
    void newRowsMerged_theSnapshotIsMadeAgain_aLedgerWithoutTheCatalogAnswersEmpty() {
        lab.start();
        SwitchLedger ledger = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC(), HELD);
        ItemCatalog before = ledger.port().catalogOf(tier("res_2")).orElseThrow();
        assertThat(before.itemsOf(11)).doesNotContainKey("1002");

        lab.item("res_2", 114, 11, "1002", "AD_view", "50");
        lab.ledger().reloadNewRows();
        ((MemLedgerPort) ledger.port()).newRowsMerged();

        ItemCatalog after = ledger.port().catalogOf(tier("res_2")).orElseThrow();
        assertThat(after).as("mutation: forget() dropped → the old snapshot stays → red").isNotSameAs(before);
        assertThat(after.itemsOf(11).get("1002")).extracting(LedgerPort.Item::id).isEqualTo(114L);
    }

    @Test
    void aLedgerThatCachesNoCatalog_answersEmpty_theWalkThenMatchesByUnit() {
        lab.start();
        com.telcobright.seed.switchledger.internal.Books onlyMoney = new com.telcobright.seed.switchledger.internal.Books(lab.ledger(), Clock.systemUTC(), Set.of(Books.ACCOUNT, Books.RESERVE, Books.PURCHASE));
        assertThat(new MemLedgerPort(onlyMoney, com.telcobright.seed.switchledger.internal.AccountLocks.of(lab.ledger(), SwitchLedgerSettings.standard().lockTimeoutMs())).catalogOf(tier("res_2"))).isEmpty();
    }

    @Test
    void theWordPackageNeverNamesTheProducts_theWordProductDoes() {
        var every = com.telcobright.seed.switchledger.internal.LedgerEntities.named(new com.telcobright.seed.switchledger.dependencies.LedgerDbSettings(
            List.of("package"), List.of(), lab.ledgerDbSettings().walDir(), lab.ledgerDbSettings().queueDir(), 5_000));
        assertThat(every).as("a tier may have no product table: 'package' = the money tables and the catalog, never the products nor the groups").doesNotContainKey(Books.PRODUCT).doesNotContainKey(Books.SERVICE_GROUP).containsKey(Books.PACKAGE_ITEM);
        var named = com.telcobright.seed.switchledger.internal.LedgerEntities.named(lab.ledgerDbSettings());
        assertThat(named).containsKey(Books.PRODUCT).containsKey(Books.SERVICE_GROUP);
        assertThat(named.get(Books.PRODUCT).readOnly()).as("§15.3: the products are WRITTEN through the cache (the portal's CRUD)").isFalse();
        assertThat(named.get(Books.SERVICE_GROUP).readOnly()).as("the service groups are read-only: loaded at the start, never written").isTrue();
    }
}
