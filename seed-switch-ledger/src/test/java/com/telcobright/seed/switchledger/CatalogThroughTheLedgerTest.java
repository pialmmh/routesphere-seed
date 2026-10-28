package com.telcobright.seed.switchledger;

import com.telcobright.seed.sessionflow.api.CachedLedger;
import com.telcobright.seed.sessionflow.api.CachedLedger.Committed;
import com.telcobright.seed.sessionflow.api.CachedLedger.Refused;
import com.telcobright.seed.switchledger.api.LedgerPackage;
import com.telcobright.seed.switchledger.api.LedgerPackageItem;
import com.telcobright.seed.switchledger.api.LedgerPurchase;
import com.telcobright.seed.switchledger.api.SwitchLedger;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerSettings;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgers;
import com.telcobright.seed.switchledger.internal.Books;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4 · the catalog through the cached ledger (the owner, 2026-10-28: "go as you recommend best" — the catalog goes through the cached
 * ledger too, so every write of the prepaid model takes ONE path): a package and its items are ONE unit, written behind like every row; a
 * purchase or an item on a package its tier's catalog lacks is refused in words, nothing written (the database's foreign keys would refuse
 * the row, and a refused batch stops the write-behind); a package an item or a purchase names is deactivated, never deleted; a package's
 * name is unique in its tier, and each tier keeps its own catalog.
 */
class CatalogThroughTheLedgerTest {

    private static final Set<String> HELD = Set.of(Books.ACCOUNT, Books.RESERVE, Books.CHARGE, Books.PURCHASE, Books.PACKAGE, Books.PACKAGE_ITEM);

    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("res_2")
        .chargeReferences().purchaseTables()
        .asLedgerDb(List.of("package"), List.of(), 5_000);

    @AfterEach
    void close() { lab.close(); }

    private CachedLedger crud() {
        SwitchLedger ledger = SwitchLedgers.over(lab.ledger(), SwitchLedgerSettings.standard(), Clock.systemUTC(), HELD);
        return ledger.port().cachedLedgerOf("btcl").orElseThrow().withIds((table, highestInUse) -> highestInUse + 1);
    }

    @Test
    void aPackageAndItsItemsAreOneUnit_writtenBehindLikeEveryRow() {
        lab.start();
        CachedLedger crud = crud();
        long records = lab.walRecords();

        CachedLedger.Unit unit = crud.unit("catalog:image-views-5000");
        long pkg = unit.insert("btcl", packageOf("Image views 5,000", "500.00"));
        unit.insert("btcl", itemOf(pkg, "5000", "AD_view"));
        unit.insert("btcl", itemOf(pkg, "50", "AD_sec"));

        assertThat(unit.commit()).isInstanceOf(Committed.class);
        assertThat(lab.walRecords()).as("ONE record: the package and its items").isEqualTo(records + 1);
        assertThat(crud.find("btcl", LedgerPackageItem.class, Map.of("idPackage", pkg))).extracting(LedgerPackageItem::getIdUom).containsExactly("AD_view", "AD_sec");
        LedgerLab.await("the write-behind", () -> lab.dbDecimal("SELECT SUM(quantity) FROM btcl.packageitem WHERE id_package = " + pkg) != null);
        assertThat(lab.dbDecimal("SELECT SUM(quantity) FROM btcl.packageitem WHERE id_package = " + pkg)).isEqualByComparingTo("5050");
        assertThat(lab.dbDecimal("SELECT basePrice FROM btcl.package WHERE id = " + pkg)).isEqualByComparingTo("500");
    }

    @Test
    void aPurchaseOrAnItemOnAPackageTheTiersCatalogLacks_isRefusedInWords_nothingWritten() {
        lab.start();
        CachedLedger crud = crud();
        long records = lab.walRecords();

        CachedLedger.Unit purchase = crud.unit("buy:77");
        purchase.insert("btcl", purchaseOf(77, 4));
        assertThat(((Refused) purchase.commit()).reason()).contains("its package 77 is not in the catalog of btcl");

        CachedLedger.Unit item = crud.unit("item:77");
        item.insert("btcl", itemOf(77, "10", "AD_view"));
        assertThat(((Refused) item.commit()).reason()).contains("its package 77 is not in the catalog of btcl");

        CachedLedger.Unit elsewhere = crud.unit("res_2-only");
        long onlyThere = elsewhere.insert("res_2", packageOf("Only in res_2", "10"));
        assertThat(elsewhere.commit()).isInstanceOf(Committed.class);
        CachedLedger.Unit wrongTier = crud.unit("buy:res_2-only");
        wrongTier.insert("btcl", purchaseOf(onlyThere, 4));
        assertThat(((Refused) wrongTier.commit()).reason()).as("each tier keeps its own catalog").contains("is not in the catalog of btcl");
        assertThat(lab.walRecords()).as("only res_2's package was written").isEqualTo(records + 1);

        CachedLedger.Unit held = crud.unit("buy:3");
        held.insert("btcl", purchaseOf(3, 4));
        assertThat(held.commit()).as("a package of the tier's catalog").isInstanceOf(Committed.class);
    }

    @Test
    void aPackageThatAnItemOrAPurchaseNames_isDeactivated_neverDeleted() {
        lab.start();
        CachedLedger crud = crud();
        CachedLedger.Unit buy = crud.unit("buy:5");
        buy.insert("btcl", purchaseOf(5, 4));
        assertThat(buy.commit()).isInstanceOf(Committed.class);
        CachedLedger.Unit items = crud.unit("items:8");
        items.insert("btcl", itemOf(8L, "100", "AD_view"));
        assertThat(items.commit()).isInstanceOf(Committed.class);

        assertThat(((Refused) crud.unit("drop:5").delete("btcl", LedgerPackage.class, 5L).commit()).reason())
            .contains("Package 5 of btcl: PackagePurchase").contains("names it — deactivate the package instead");
        assertThat(((Refused) crud.unit("drop:8").delete("btcl", LedgerPackage.class, 8L).commit()).reason())
            .contains("Package 8 of btcl: PackageItem").contains("names it");

        assertThat(crud.unit("off:5").update("btcl", LedgerPackage.class, 5L, Map.of("activeStatus", false)).commit()).isInstanceOf(Committed.class);
        assertThat(crud.get("btcl", LedgerPackage.class, 5L).orElseThrow().getActiveStatus()).isFalse();
        assertThat(crud.unit("drop:6").delete("btcl", LedgerPackage.class, 6L).commit()).as("a package nobody names").isInstanceOf(Committed.class);
        assertThat(crud.get("btcl", LedgerPackage.class, 6L)).isEmpty();
    }

    @Test
    void aPackagesNameIsUniqueInItsTier_andItsShapeIsTheNarrowerSchemas() {
        lab.start();
        CachedLedger crud = crud();

        CachedLedger.Unit same = crud.unit("dup");
        same.insert("btcl", packageOf("Package 3", "10"));
        assertThat(((Refused) same.commit()).reason()).contains("name Package 3 is Package 3's already — the column is unique");

        LedgerPackage noVat = packageOf("No VAT given", "10");
        noVat.setVatPercent(null);
        CachedLedger.Unit empty = crud.unit("empty");
        empty.insert("btcl", noVat);
        assertThat(((Refused) empty.commit()).reason()).contains("vatPercent is empty, its column may not be");

        CachedLedger.Unit longUnit = crud.unit("long-uom");
        longUnit.insert("btcl", itemOf(3, "1", "A_UNIT_NAME_OVER_TWENTY"));
        assertThat(((Refused) longUnit.commit()).reason()).as("RTC-Manager's MySQL: id_UOM varchar(20)").contains("idUom is 23 characters, its column holds 20");
    }

    /** The word {@code package} keeps naming every table (the catalog too); {@code catalog} names the catalog beside a list of money tables. */
    @Test
    void theWordPackageNamesEveryTable_theWordCatalogNamesTheCatalog() {
        java.nio.file.Path dir = java.nio.file.Path.of("unused");
        Set<String> every = com.telcobright.seed.switchledger.internal.LedgerEntities.named(
            new com.telcobright.seed.switchledger.dependencies.LedgerDbSettings(List.of("package"), List.of(), dir, dir, 5_000)).keySet();
        Set<String> listed = com.telcobright.seed.switchledger.internal.LedgerEntities.named(new com.telcobright.seed.switchledger.dependencies.LedgerDbSettings(
            List.of("packageaccount", "packageaccountreserve", "packagepurchase", "catalog"), List.of(), dir, dir, 5_000)).keySet();
        assertThat(every).containsExactlyInAnyOrderElementsOf(LedgerLab.EVERY_ENTITY);
        assertThat(listed).containsExactlyInAnyOrder(Books.ACCOUNT, Books.RESERVE, Books.PURCHASE, Books.PACKAGE, Books.PACKAGE_ITEM);
    }

    static LedgerPackage packageOf(String name, String basePrice) {
        LedgerPackage p = new LedgerPackage();
        p.setName(name);
        p.setBasePrice(new BigDecimal(basePrice));
        p.setVatPercent(new BigDecimal("15"));
        p.setAitPercent(new BigDecimal("5"));
        p.setCreateDate(LocalDateTime.of(2026, 10, 28, 6, 0));
        p.setValidity(30L * 24 * 3600);
        p.setActiveStatus(true);
        return p;
    }

    static LedgerPackageItem itemOf(long packageId, String quantity, String uom) {
        LedgerPackageItem i = new LedgerPackageItem();
        i.setIdPackage(packageId);
        i.setQuantity(new BigDecimal(quantity));
        i.setIdUom(uom);
        i.setCategory(1);
        i.setPrefix("*");
        i.setDescription(quantity + " " + uom);
        return i;
    }

    static LedgerPurchase purchaseOf(long packageId, long partnerId) {
        LedgerPurchase p = LedgerPurchase.made(0, packageId, partnerId, LocalDateTime.of(2026, 10, 28, 3, 0), LocalDateTime.of(2026, 11, 28, 3, 0), new BigDecimal("500"));
        p.setId(null);
        return p;
    }
}
