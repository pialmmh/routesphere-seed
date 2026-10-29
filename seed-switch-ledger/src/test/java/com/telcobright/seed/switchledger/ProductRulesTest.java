package com.telcobright.seed.switchledger;

import com.telcobright.seed.sessionflow.api.CachedLedger;
import com.telcobright.seed.sessionflow.api.CachedLedger.Committed;
import com.telcobright.seed.sessionflow.api.CachedLedger.Refused;
import com.telcobright.seed.switchledger.api.LedgerProduct;
import com.telcobright.seed.switchledger.api.SwitchLedger;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.Books;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §15.3 · A product is WRITTEN through the cached ledger like a package (ONE unit, written behind), and refused in words before the
 * record: a kind outside the platform's services, a source outside portal | odoo, an ad product without its media, seconds on an image,
 * a service group the tier does not know; a product an item names is deactivated, never deleted; the catalog snapshot answers the new
 * product at once. Mutations seen red: the kind check dropped; the group check dropped; the delete guard dropped; the snapshot not remade.
 */
class ProductRulesTest {

    private static final Set<String> HELD = Set.of(Books.ACCOUNT, Books.RESERVE, Books.PURCHASE, Books.PACKAGE, Books.PACKAGE_ITEM, Books.PRODUCT, Books.SERVICE_GROUP);

    private final LedgerLab lab = LedgerLab.open().schema("res_2").purchaseTables().productTable().serviceGroupTable()
        .item("res_2", 21, 2, "1001", "AD_view", "100")
        .item("res_2", 24, 4, "1001", "AD_view", "100").item("res_2", 25, 4, "1003", "AD_view", "500")
        .product("res_2", "1001", 1001, "Video ad 30 s", "ad", "video", 30, "view", 30)
        .product("res_2", "1003", 1003, "Image ad", "ad", "image", null, "view", 30)
        .product("res_2", "2001", 0, "WiFi one hour", "wifi-internet", null, null, "TF_min", 101)
        .asLedgerDb(List.of("packageaccount", "packageaccountreserve", "packagepurchase", "catalog", "product", "servicegroup"), List.of("packageaccountreserve"), 5_000);
    private SwitchLedger ledger;

    @AfterEach
    void close() { lab.close(); }

    private CachedLedger crud() {
        lab.start();
        ledger = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC(), HELD);
        return ledger.port().cachedLedgerOf("btcl").orElseThrow().withIds((table, highestInUse) -> highestInUse + 1);
    }

    /** One row inserted and the unit committed (a unit's insert answers the new key, not the unit). */
    private static CachedLedger.Outcome inserted(CachedLedger crud, String reference, Object row) {
        CachedLedger.Unit unit = crud.unit(reference);
        unit.insert("res_2", row);
        return unit.commit();
    }

    private static com.telcobright.seed.switchledger.api.LedgerPackageItem item(long packageId, String key, String uom) {
        com.telcobright.seed.switchledger.api.LedgerPackageItem i = new com.telcobright.seed.switchledger.api.LedgerPackageItem();
        i.setIdPackage(packageId);
        i.setQuantity(new java.math.BigDecimal("10"));
        i.setIdUom(uom);
        i.setCategory(1);
        i.setPrefix(key);
        i.setDescription("10 " + uom + " of " + key);
        return i;
    }

    private static LedgerProduct product(String key, String kind, String media, Integer seconds, int group) {
        LedgerProduct p = new LedgerProduct();
        p.setProductKey(key);
        p.setName("Product " + key);
        p.setKind(kind);
        p.setMedia(media);
        p.setSeconds(seconds);
        p.setUom("view");
        p.setActive(true);
        p.setSource("portal");
        p.setServiceGroupId(group);
        return p;
    }

    private static com.telcobright.rtc.domainmodel.LevelAdmission tier(String db) {
        com.telcobright.rtc.domainmodel.nonentity.Tenant t = new com.telcobright.rtc.domainmodel.nonentity.Tenant();
        t.setDbName(db);
        return new com.telcobright.rtc.domainmodel.LevelAdmission(0, t, null, null);
    }

    @Test
    void aProductIsOneUnit_writtenBehind_andTheCatalogSnapshotAnswersItAtOnce() {
        CachedLedger crud = crud();
        long records = lab.walRecords();
        var before = ledger.port().catalogOf(tier("res_2")).orElseThrow();

        CachedLedger.Outcome made = inserted(crud, "product:1002", product("1002", "ad", "video", 60, 30));

        assertThat(made).isInstanceOf(Committed.class);
        assertThat(lab.walRecords()).isEqualTo(records + 1);
        assertThat(crud.get("res_2", LedgerProduct.class, "1002")).get().extracting(LedgerProduct::getSeconds, LedgerProduct::getServiceGroupId).containsExactly(60, 30);
        var after = ledger.port().catalogOf(tier("res_2")).orElseThrow();
        assertThat(after).as("mutation: a catalog write not told to the index → the old snapshot stays → red").isNotSameAs(before);
        assertThat(after.productOf("1002")).isPresent();
        LedgerLab.await("the write-behind", () -> lab.dbDecimal("SELECT seconds FROM res_2.product_catalog WHERE product_key = '1002'") != null);
        assertThat(lab.dbDecimal("SELECT service_group_id FROM res_2.product_catalog WHERE product_key = '1002'")).isEqualByComparingTo("30");
    }

    @Test
    void refusedInWords_beforeTheRecord() {
        CachedLedger crud = crud();
        long records = lab.walRecords();

        assertThat(reason(inserted(crud, "p1", product("3001", "snack", "video", 30, 30)))).as("mutation: the kind check dropped → red").contains("kind 'snack' is not one of");
        assertThat(reason(inserted(crud, "p2", product("3002", "ad", null, null, 30)))).contains("an ad product names its media");
        assertThat(reason(inserted(crud, "p3", product("3003", "ad", "image", 30, 30)))).contains("seconds belong to a video or audio product");
        assertThat(reason(inserted(crud, "p4", product("3004", "ad", "video", 30, 77)))).as("mutation: the group check dropped → red").contains("service group 77 is not one res_2 knows");
        LedgerProduct odd = product("3005", "voice", null, null, 1);
        odd.setSource("excel");
        assertThat(reason(inserted(crud, "p5", odd))).contains("source 'excel' is not one of");
        assertThat(reason(inserted(crud, "p6", product("1001", "ad", "video", 30, 30)))).as("a key taken").isNotNull();
        assertThat(inserted(crud, "p7", product("3006", "voice", null, null, 1))).as("a voice product: no media, no seconds, group 1").isInstanceOf(Committed.class);
        assertThat(lab.walRecords()).as("six refusals wrote nothing; one product one record").isEqualTo(records + 1);
    }

    @Test
    void aProductAnItemNames_isNeverDeleted_deactivatedInstead() {
        CachedLedger crud = crud();

        CachedLedger.Outcome deleted = crud.unit("d1").delete("res_2", LedgerProduct.class, "1001").commit();
        assertThat(reason(deleted)).as("mutation: the delete guard dropped → red").containsPattern("PackageItem (21|24) names it").contains("deactivate the product instead");

        CachedLedger.Outcome off = crud.unit("d2").update("res_2", LedgerProduct.class, "1001", Map.of("active", false)).commit();
        assertThat(off).isInstanceOf(Committed.class);
        assertThat(crud.get("res_2", LedgerProduct.class, "1001")).get().extracting(LedgerProduct::getActive).isEqualTo(false);
    }

    /**
     * ONE SERVICE GROUP PER PACKAGE (ruled 2026-10-30): package 2 holds the ad's video (group 30) — an item of the WiFi product (group 101)
     * is refused there and taken by another package; an item naming no product (the call's prefix) is not held to it; package 4 holds two
     * ad products — moving one of them to group 101 is refused, nothing written. Mutations: the item check dropped → red; the product
     * check dropped → red.
     */
    @Test
    void onePackage_oneServiceGroup_anItemOrAProductChangeThatMixesIsRefused() {
        CachedLedger crud = crud();
        long records = lab.walRecords();

        assertThat(reason(inserted(crud, "i1", item(2, "2001", "TF_min")))).as("mutation: the item check dropped → red")
            .contains("package 2 of res_2 would hold items of service groups [30, 101]").contains("one service group per package");
        assertThat(inserted(crud, "i2", item(3, "2001", "TF_min"))).as("another package takes it").isInstanceOf(Committed.class);
        assertThat(inserted(crud, "i3", item(2, "880", "TF_min"))).as("an item naming no product carries no group").isInstanceOf(Committed.class);

        CachedLedger.Unit move = crud.unit("g1");
        move.update("res_2", LedgerProduct.class, "1003", Map.of("serviceGroupId", 101));
        assertThat(reason(move.commit())).as("mutation: the product check dropped → red").contains("package 4 of res_2 would hold items of service groups [30, 101]");
        assertThat(crud.get("res_2", LedgerProduct.class, "1003")).get().extracting(LedgerProduct::getServiceGroupId).isEqualTo(30);

        CachedLedger.Unit alone = crud.unit("g2");
        alone.update("res_2", LedgerProduct.class, "2001", Map.of("serviceGroupId", 1));
        assertThat(alone.commit()).as("a product whose packages hold nothing else may change its group").isInstanceOf(Committed.class);
        assertThat(lab.walRecords()).as("two refusals wrote nothing").isEqualTo(records + 3);
    }

    private static String reason(CachedLedger.Outcome outcome) { return outcome instanceof Refused r ? r.reason() : null; }
}
