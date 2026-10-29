package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.prepaid.api.PackageItemMatch;
import com.telcobright.seed.sessionflow.spi.LedgerPort.CatalogPackage;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Item;
import com.telcobright.seed.sessionflow.spi.LedgerPort.ItemCatalog;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Product;
import com.telcobright.seed.switchledger.api.LedgerAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §15 steps 2–3, pure: over the accounts that may pay (best first), each package account related to the item of its package by the
 * request's keys — an account that NAMES its item pays only when that item's key is asked; an account of no item (before §15) through its
 * purchase's package, the first asked key its items carry, the item counting the account's unit; cash kept as it is. Mutations seen red:
 * the named item's key not checked (a video package pays an image); the legacy item's unit not checked; the keys' order ignored.
 */
class ItemLookupTest {

    private static final Item VIDEO_30 = new Item(111, 11, "1001", "AD_view", new BigDecimal("100"), 1, "video 30 s views");
    private static final Item IMAGE = new Item(112, 11, "1003", "AD_view", new BigDecimal("500"), 1, "image views");
    private static final Item MINUTES_880 = new Item(21, 2, "880", "TF_min", new BigDecimal("300"), 1, "Bangladesh minutes");
    private static final Item MINUTES_8801 = new Item(22, 2, "8801", "TF_min", new BigDecimal("100"), 1, "Bangladesh mobile minutes");
    private static final Item MESSAGES_880 = new Item(23, 2, "880", "OTH_ea", new BigDecimal("50"), 1, "Bangladesh messages");

    /** A catalog of two packages: 11 (the ad's combo) and 2 (the call's minutes and messages); products for the ad's keys only. */
    private static final ItemCatalog CATALOG = new ItemCatalog() {
        @Override public Optional<CatalogPackage> packageOf(long id) { return Optional.empty(); }
        @Override public Map<String, Item> itemsOf(long packageId) {
            return packageId == 11 ? Map.of("1001", VIDEO_30, "1003", IMAGE) : packageId == 2 ? Map.of("880", MINUTES_880, "8801", MINUTES_8801) : Map.of();
        }
        @Override public Optional<Item> itemOf(long itemId) { return List.of(VIDEO_30, IMAGE, MINUTES_880, MINUTES_8801, MESSAGES_880).stream().filter(i -> i.id() == itemId).findFirst(); }
        @Override public Optional<Long> packageOfPurchase(long purchaseId) { return purchaseId == 5 ? Optional.of(11L) : purchaseId == 7 ? Optional.of(2L) : Optional.empty(); }
        @Override public Map<String, Product> products() { return Map.of("1001", new Product("1001", "V30", "Video ad 30 s", "video", 30, "view", true)); }
    };

    private static PackageAccount named(long id, long purchase, String uom, Long itemId) {
        LedgerAccount a = new LedgerAccount();
        a.setId(id);
        a.setIdpackagePurchase(purchase);
        a.setUom(uom);
        a.setIdPackageItem(itemId);
        return a;
    }

    @Test
    void anAccountThatNamesItsItem_paysOnlyWhenThatItemsKeyIsAsked_cashKept_theProductComesWithTheMatch() {
        PackageAccount videos = named(51, 5, "AD_view", 111L), images = named(52, 5, "AD_view", 112L), cash = named(54, 6, "BDT", null);

        ItemLookup.Payable aVideo = ItemLookup.match(List.of(images, videos, cash), List.of("1001"), CATALOG);
        assertThat(aVideo.accounts()).as("mutation: the named item's key not checked → the image package pays a video → red").containsExactly(videos, cash);
        assertThat(aVideo.anyPackageMatched()).isTrue();
        PackageItemMatch match = aVideo.matchOf(videos);
        assertThat(match.item()).isSameAs(VIDEO_30);
        assertThat(match.key()).isEqualTo("1001");
        assertThat(match.seconds()).isEqualTo(30);
        assertThat(match.kind()).isEqualTo("video");
        assertThat(aVideo.matchOf(cash)).isNull();

        ItemLookup.Payable anImage = ItemLookup.match(List.of(images, videos, cash), List.of("1003"), CATALOG);
        assertThat(anImage.accounts()).containsExactly(images, cash);
        assertThat(anImage.matchOf(images).product()).as("no product cached for the image's key: the match carries none").isNull();

        ItemLookup.Payable aLongVideo = ItemLookup.match(List.of(images, videos, cash), List.of("1002"), CATALOG);
        assertThat(aLongVideo.accounts()).as("no package has the item: cash alone").containsExactly(cash);
        assertThat(aLongVideo.anyPackageMatched()).isFalse();
        assertThat(ItemLookup.match(List.of(images, videos), List.of("1002"), CATALOG).accounts()).as("no package, no cash: nothing — the walk says PACKAGE_ITEM_NOT_FOUND").isEmpty();
    }

    @Test
    void anAccountOfNoItem_throughItsPurchasesPackage_theFirstAskedKeyItsItemsCarry_countingTheAccountsUnit() {
        PackageAccount minutes = named(41, 7, "TF_min", null), messages = named(42, 7, "OTH_ea", null), orphan = named(43, 9, "TF_min", null);
        List<String> keys = List.of("88017", "8801", "880");          // the called number's prefixes, longest first

        ItemLookup.Payable aCall = ItemLookup.match(List.of(minutes, messages, orphan), keys, CATALOG);
        assertThat(aCall.accounts()).as("the messages' account is no match for a call: its package's '880' item counts OTH_ea, not its unit — mutation: unit not checked → red").containsExactly(minutes);
        assertThat(aCall.matchOf(minutes).item()).as("the longest prefix that hits: 8801 before 880 — mutation: order ignored → red").isSameAs(MINUTES_8801);
        assertThat(aCall.matchOf(minutes).product()).isNull();
        assertThat(aCall.matchOf(minutes).key()).isEqualTo("8801");

        assertThat(ItemLookup.match(List.of(orphan), keys, CATALOG).accounts()).as("a purchase the catalog does not know: no item").isEmpty();
        assertThat(ItemLookup.match(List.of(minutes), List.of(), CATALOG).accounts()).as("no key asked: no package item").isEmpty();
    }
}
