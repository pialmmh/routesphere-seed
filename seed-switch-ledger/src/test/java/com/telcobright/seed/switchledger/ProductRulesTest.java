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
        .product("res_2", "1001", 1001, "Video ad 30 s", "ad", "video", 30, "view", 30)
        .asLedgerDb(List.of("packageaccount", "packageaccountreserve", "packagepurchase", "catalog", "product", "servicegroup"), List.of("packageaccountreserve"), 5_000);
    private SwitchLedger ledger;

    @AfterEach
    void close() { lab.close(); }

    private CachedLedger crud() {
        lab.start();
        ledger = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC(), HELD);
        return ledger.port().cachedLedgerOf("btcl").orElseThrow().withIds((table, highestInUse) -> highestInUse + 1);
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

        CachedLedger.Outcome made = crud.unit("product:1002").insert("res_2", product("1002", "ad", "video", 60, 30)).commit();

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

        assertThat(reason(crud.unit("p1").insert("res_2", product("2001", "snack", "video", 30, 30)).commit())).as("mutation: the kind check dropped → red").contains("kind 'snack' is not one of");
        assertThat(reason(crud.unit("p2").insert("res_2", product("2002", "ad", null, null, 30)).commit())).contains("an ad product names its media");
        assertThat(reason(crud.unit("p3").insert("res_2", product("2003", "ad", "image", 30, 30)).commit())).contains("seconds belong to a video or audio product");
        assertThat(reason(crud.unit("p4").insert("res_2", product("2004", "ad", "video", 30, 77)).commit())).as("mutation: the group check dropped → red").contains("service group 77 is not one res_2 knows");
        LedgerProduct odd = product("2005", "voice", null, null, 1);
        odd.setSource("excel");
        assertThat(reason(crud.unit("p5").insert("res_2", odd).commit())).contains("source 'excel' is not one of");
        assertThat(reason(crud.unit("p6").insert("res_2", product("1001", "ad", "video", 30, 30)).commit())).as("a key taken").isNotNull();
        assertThat(crud.unit("p7").insert("res_2", product("2006", "voice", null, null, 1)).commit()).as("a voice product: no media, no seconds, group 1").isInstanceOf(Committed.class);
        assertThat(lab.walRecords()).as("six refusals wrote nothing; one product one record").isEqualTo(records + 1);
    }

    @Test
    void aProductAnItemNames_isNeverDeleted_deactivatedInstead() {
        CachedLedger crud = crud();

        CachedLedger.Outcome deleted = crud.unit("d1").delete("res_2", LedgerProduct.class, "1001").commit();
        assertThat(reason(deleted)).as("mutation: the delete guard dropped → red").contains("PackageItem 21 names it").contains("deactivate the product instead");

        CachedLedger.Outcome off = crud.unit("d2").update("res_2", LedgerProduct.class, "1001", Map.of("active", false)).commit();
        assertThat(off).isInstanceOf(Committed.class);
        assertThat(crud.get("res_2", LedgerProduct.class, "1001")).get().extracting(LedgerProduct::getActive).isEqualTo(false);
    }

    private static String reason(CachedLedger.Outcome outcome) { return outcome instanceof Refused r ? r.reason() : null; }
}
