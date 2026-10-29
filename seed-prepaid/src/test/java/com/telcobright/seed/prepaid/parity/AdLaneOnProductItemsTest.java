package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.statewalk.session.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static com.telcobright.seed.prepaid.parity.AdLaneOnItems.AN_IMAGE;
import static com.telcobright.seed.prepaid.parity.AdLaneOnItems.A_VIDEO_30;
import static com.telcobright.seed.prepaid.parity.AdLaneOnItems.A_VIDEO_60;
import static com.telcobright.seed.prepaid.parity.AdLaneOnItems.A_VIDEO_90;
import static com.telcobright.seed.prepaid.parity.AdLaneOnItems.A_VIDEO_OF_101;
import static com.telcobright.seed.prepaid.parity.ParityScene.ADCO;
import static com.telcobright.seed.prepaid.parity.ParityScene.RESELLER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * §15 · The owner's ad flow on the REAL switch ledger with the catalog cached ({@link AdLaneOnItems}). At res_2, Adco (705) holds
 * 51 (purchase 11 → package 11, the item "video 30 s" {@code 1001}, 100 views, 2030), 52 (purchase 12 → package 12, the item "image"
 * {@code 1003}, 5 views, 2027 — the soonest), 53 (purchase 13 → package 13: an account of NO item, 300 {@code AD_sec}, whose package has
 * the item "video 60 s" {@code 1002} counted in {@code AD_sec}), 54 cash 20 and 55 expired. At the root, the reseller (2) holds 23
 * (purchase 8 → package 21, the item "image", 50 views) beside its minutes (21) and cash (22): the root tier matches by item too. The
 * product "video 90 s" {@code 1004} is in no package. Mutations seen red: the item's key ignored in the walk (52 pays a video); the cause
 * left at INSUFFICIENT_BALANCE when no item exists; the match's key not put on the level.
 */
class AdLaneOnProductItemsTest {

    private ParityScene scene;

    @AfterEach
    void close() { if (scene != null) scene.close(); }

    /** The catalog: packages 11–13 at res_2 and 21 at btcl with their items and the products; the purchases moved onto them; the accounts naming their items. */
    static void catalogOf(LedgerLab lab) {
        for (long p : new long[] {11, 12, 13, 14}) {
            lab.ddl("INSERT INTO res_2.package (id, name, basePrice, vatPercent, createDate, validity, aitPercent, activeStatus) VALUES (" + p + ", 'Package " + p + " of items', 100, 0, TIMESTAMP '2026-01-01 00:00:00', 2592000, 0, TRUE)");
            lab.ddl("UPDATE res_2.packagepurchase SET id_package = " + p + " WHERE id = " + p);
        }
        lab.item("res_2", 111, 11, AdLaneOnItems.VIDEO_30, "AD_view", "100").item("res_2", 121, 12, AdLaneOnItems.IMAGE, "AD_view", "5")
            .item("res_2", 131, 13, AdLaneOnItems.VIDEO_60, "AD_sec", "300").item("res_2", 141, 14, AdLaneOnItems.VIDEO_OF_101, "AD_view", "50");
        for (String schema : new String[] {"res_2", "btcl"}) {
            lab.product(schema, AdLaneOnItems.VIDEO_30, 1001, "Video ad 30 s", "ad", "video", 30, "view", AdLaneOnItems.AD_GROUP).product(schema, AdLaneOnItems.VIDEO_60, 1002, "Video ad 60 s", "ad", "video", 60, "view", AdLaneOnItems.AD_GROUP)
                .product(schema, AdLaneOnItems.IMAGE, 1003, "Image ad", "ad", "image", null, "view", AdLaneOnItems.AD_GROUP).product(schema, AdLaneOnItems.VIDEO_90, 1004, "Video ad 90 s", "ad", "video", 90, "view", AdLaneOnItems.AD_GROUP)
                .product(schema, AdLaneOnItems.VIDEO_OF_101, 2001, "Video ad 30 s of the WiFi lane", "ad", "video", 30, "view", AdLaneOnItems.OTHER_GROUP);
        }
        lab.ddl("UPDATE res_2.packageaccount SET id_packageitem = 111 WHERE id_packageaccount = 51");
        lab.ddl("UPDATE res_2.packageaccount SET id_packageitem = 121 WHERE id_packageaccount = 52");
        // §15.3 · a package of Adco whose item's product belongs to group 101: account 56 on purchase 14 (package 14)
        lab.purchase("res_2", 14, ADCO, "2030-01-01 00:00:00").ddl("UPDATE res_2.packagepurchase SET id_package = 14 WHERE id = 14");
        lab.accountOfItem("res_2", 56, 14, 141, "AD_view", "50");
        lab.ddl("INSERT INTO btcl.package (id, name, basePrice, vatPercent, createDate, validity, aitPercent, activeStatus) VALUES (21, 'Reseller image views', 100, 0, TIMESTAMP '2026-01-01 00:00:00', 2592000, 0, TRUE)");
        lab.item("btcl", 211, 21, AdLaneOnItems.IMAGE, "AD_view", "50");
        lab.purchase("btcl", 8, RESELLER, "2030-01-01 00:00:00").ddl("UPDATE btcl.packagepurchase SET id_package = 21 WHERE id = 8");
        lab.accountOfItem("btcl", 23, 8, 211, "AD_view", "50");
    }

    /** P1 · An image view: at res_2 the soonest package has the image item → one view; the video package is untouched; at the root the reseller's image package pays, not its cash. */
    @Test
    void P1_anImageView_theImageItemsAccountPaysOneView_onBothTiers() {
        scene = open(AdLaneOnItems::onPrepaid);

        CallContext view = scene.admitted("i1", ADCO, AN_IMAGE);

        assertThat(scene.balance("res_2", 52)).as("the image item's account").isEqualByComparingTo("4");
        assertThat(scene.balance("res_2", 51)).as("the video item's account never pays an image").isEqualByComparingTo("100");
        assertThat(scene.balance("res_2", 54)).as("a package paid: no cash").isEqualByComparingTo("20");
        assertThat(scene.balance("btcl", 23)).as("the root tier matches by item too: the reseller's image package").isEqualByComparingTo("49");
        assertThat(scene.balance("btcl", 22)).as("its cash untouched").isEqualByComparingTo("50");
        assertThat(view.levels).extracting(LevelAdmission::getRatePrefix).as("the matched key is the level's prefix (the CDR's) — mutation: key not put on the level → red")
            .containsExactly(AdLaneOnItems.IMAGE, AdLaneOnItems.IMAGE);
        assertThat(scene.rows("res_2")).as("charged now: no hold row").isZero();
    }

    /** P2 · A 30-second video: the SOONEST package is the image package — its item is no match, so the video item's account pays; the root has no video item → its cash. */
    @Test
    void P2_aVideoView_theSoonestPackageHasNoVideoItem_theVideoItemsAccountPays_theRootPaysCash() {
        scene = open(AdLaneOnItems::onPrepaid);

        scene.admitted("v1", ADCO, A_VIDEO_30);

        assertThat(scene.balance("res_2", 51)).as("one view of the 30 s video product").isEqualByComparingTo("99");
        assertThat(scene.balance("res_2", 52)).as("mutation: the item's key ignored → the soonest (image) package pays → red").isEqualByComparingTo("5");
        assertThat(scene.balance("res_2", 53)).as("the seconds account's package has the 60 s item only").isEqualByComparingTo("300");
        assertThat(scene.balance("btcl", 23)).as("the reseller's image package never pays a video").isEqualByComparingTo("50");
        assertThat(scene.balance("btcl", 22)).as("the reseller's cash at the price").isEqualByComparingTo("49.60");
    }

    /** P3 · A 60-second video: no account NAMES that item, but the old seconds account's package carries it in the account's unit → it pays the seconds (an account of before §15). */
    @Test
    void P3_aLongerVideo_anAccountOfNoItemPaysThroughItsPackagesItem_inItsOwnUnit() {
        scene = open(AdLaneOnItems::onPrepaid);

        scene.admitted("v2", ADCO, A_VIDEO_60);

        assertThat(scene.balance("res_2", 53)).as("300 seconds less the video's 60").isEqualByComparingTo("240");
        assertThat(scene.balance("res_2", 51)).isEqualByComparingTo("100");
        assertThat(scene.balance("res_2", 54)).isEqualByComparingTo("20");
    }

    /** P4 · A product NO package has an item for (the 90 s video): cash pays the rate-plan price; with a lane that allows no cash, the view is refused PACKAGE_ITEM_NOT_FOUND and nothing moves. */
    @Test
    void P4_noPackageHasTheItem_cashPaysThePrice_orRefusedWhenTheLaneAllowsNoCash() {
        scene = open(AdLaneOnItems::onPrepaid);
        scene.admitted("v3", ADCO, A_VIDEO_90);
        assertThat(scene.balance("res_2", 54)).as("cash at the rate plan's price").isEqualByComparingTo("19.60");
        assertThat(scene.balance("res_2", 51)).isEqualByComparingTo("100");
        assertThat(scene.balance("res_2", 53)).isEqualByComparingTo("300");
        scene.close();

        scene = open(AdLaneOnItems::packagesOnly);
        Verdict refused = scene.admit(scene.call("v4", ADCO, A_VIDEO_90));
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.cause()).as("the owner's 'rate/package not found' — mutation: the cause left at INSUFFICIENT_BALANCE → red").isEqualTo(SessionCause.PACKAGE_ITEM_NOT_FOUND);
        assertThat(scene.balance("res_2", 54)).as("nothing moved").isEqualByComparingTo("20");
        assertThat(scene.balance("res_2", 51)).isEqualByComparingTo("100");
    }

    /** P5 · The packages-only lane pays from the matching items on both tiers; two image views, two units each. */
    @Test
    void P5_thePackagesOnlyLane_paysFromTheMatchingItems_onBothTiers() {
        scene = open(AdLaneOnItems::packagesOnly);

        scene.admitted("i2", ADCO, AN_IMAGE);
        scene.admitted("i3", ADCO, AN_IMAGE);

        assertThat(scene.balance("res_2", 52)).isEqualByComparingTo("3");
        assertThat(scene.balance("btcl", 23)).isEqualByComparingTo("48");
        assertThat(scene.balance("res_2", 54)).isEqualByComparingTo("20");
        assertThat(scene.balance("btcl", 22)).isEqualByComparingTo("50");
    }

    /** P6 · The item EXISTS but its account is empty (the seconds account at 0): that is INSUFFICIENT_BALANCE, not a missing item. */
    @Test
    void P6_theItemExistsButItsAccountIsEmpty_isInsufficientBalance_notAMissingItem() {
        scene = open(AdLaneOnItems::packagesOnly, Map.of("res_2#53", "0"));

        Verdict refused = scene.admit(scene.call("v5", ADCO, A_VIDEO_60));

        assertThat(refused.accepted()).isFalse();
        assertThat(refused.cause()).isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(scene.balance("res_2", 51)).isEqualByComparingTo("100");
    }

    /** P7 · §15.3 step 1b · The video product of group 101 is in Adco's package 14, keyed as the view asks — the ad lane (group 30) never pays from it: cash pays. */
    @Test
    void P7_anItemOfAnotherServiceGroup_neverPaysTheRequest_cashDoes() {
        scene = open(AdLaneOnItems::onPrepaid);

        scene.admitted("g1", ADCO, A_VIDEO_OF_101);

        assertThat(scene.balance("res_2", 56)).as("mutation: the group not checked → the group-101 package pays the ad lane's view → red").isEqualByComparingTo("50");
        assertThat(scene.balance("res_2", 54)).as("no item of the request's group: cash at the price").isEqualByComparingTo("19.60");
    }

    /** P8 · §15.3 · A lane of group 101 asking the same key: the item pays, and the charge record of that tier carries the PRODUCT's group (101), the cash tier the lane's (30). */
    @Test
    void P8_theChargeRecordCarriesTheMatchedProductsGroup_theCashTierTheLanes() {
        scene = open(AdLaneOnItems::ofGroup101);

        CallContext view = scene.admitted("g2", ADCO, A_VIDEO_OF_101);
        scene.answer(view);
        scene.flow.settle(view, com.telcobright.statewalk.session.Outcome.SUCCEEDED);

        assertThat(scene.balance("res_2", 56)).isEqualByComparingTo("49");
        java.util.List<com.telcobright.seed.sessionflow.api.CdrEvent> tiers = scene.cdrs.of("g2").get(0).tiers();
        assertThat(tiers).extracting(t -> t.serviceGroup).as("the leaf's record: the product's group 101; the root's (cash): the lane's 30 — mutation: the matched group not remembered → 30, 30 → red")
            .containsExactly(AdLaneOnItems.OTHER_GROUP, AdLaneOnItems.AD_GROUP);
    }

    private static ParityScene open(Function<com.telcobright.seed.sessionflow.dependencies.RoutesphereKit, com.telcobright.seed.sessionflow.api.RoutesphereFlow<CallContext>> lane) {
        return open(lane, Map.of());
    }

    private static ParityScene open(Function<com.telcobright.seed.sessionflow.dependencies.RoutesphereKit, com.telcobright.seed.sessionflow.api.RoutesphereFlow<CallContext>> lane, Map<String, String> balances) {
        return ParityScene.openOnProductItems(balances, lane, AdLaneOnProductItemsTest::catalogOf);
    }
}
