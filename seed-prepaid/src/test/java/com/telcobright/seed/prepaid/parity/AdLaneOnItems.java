package com.telcobright.seed.prepaid.parity;

import com.telcobright.seed.prepaid.api.AccountRule;
import com.telcobright.seed.prepaid.api.Admission;
import com.telcobright.seed.prepaid.api.PackageMatch;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.prepaid.api.ProductKeys;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereApp;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * §15 · The ad lane on PRODUCT ITEMS (the owner, 2026-10-29): a package item is a product — "video 30 s" (key {@value #VIDEO_30}),
 * "video 60 s" ({@value #VIDEO_60}), "image" ({@value #IMAGE}) — and its quantity is VIEWS; a view consumes ONE view of the product it
 * is (the called number says which here: an ad content would carry its product). The owner's four steps: the best package first, the
 * item by key, no item → cash at the rate-plan price (or refused when the lane allows no cash), the deduction.
 */
public final class AdLaneOnItems {

    public static final String AD_VIEW = "AD_view", AD_SEC = "AD_sec", PER_VIEW = "0.40";
    public static final String VIDEO_30 = "1001", VIDEO_60 = "1002", IMAGE = "1003";
    /** A product of the catalog that NO package has an item for. */
    public static final String VIDEO_90 = "1004";
    /** §15.3 · A product of ANOTHER service group (101, the WiFi lane's) that a package does hold. */
    public static final String VIDEO_OF_101 = "2001";
    public static final int AD_GROUP = 30, OTHER_GROUP = 101;
    /** The called numbers that say what the view is. */
    public static final String A_VIDEO_30 = "8801700000030", A_VIDEO_60 = "8801700000060", A_VIDEO_90 = "8801700000090", A_VIDEO_OF_101 = "8801700000101", AN_IMAGE = "8801700000000";

    private AdLaneOnItems() {}

    public static RoutesphereFlow<CallContext> onPrepaid(RoutesphereKit kit) { return lane(kit, AccountRule.UNITS_THEN_CASH, AD_GROUP); }

    /** The same lane letting no cash pay: no matching item = refused. */
    public static RoutesphereFlow<CallContext> packagesOnly(RoutesphereKit kit) { return lane(kit, AccountRule.UNITS_ONLY, AD_GROUP); }

    /** §15.3 · A lane whose requests belong to group 101 (the CDR's lane group stays 30): the matched product's group goes on the record. */
    public static RoutesphereFlow<CallContext> ofGroup101(RoutesphereKit kit) { return lane(kit, AccountRule.UNITS_THEN_CASH, OTHER_GROUP); }

    private static RoutesphereFlow<CallContext> lane(RoutesphereKit kit, AccountRule accounts, int group) {
        return RoutesphereApp.<CallContext>builder("AdSupervisor")
            .serviceGroup(30)
            .identifyRootLevelTenant((facts, find) -> find.byIngress(facts.ingress()))
            .identifyPartner((view, candidate, find) -> find.byPartnerId(view, ((CallFacts) view.facts).partnerId()))
            .buildTask(AdLane::taskOf)
            .route(view -> RoutePlan.one(new ParityWire.Hop("creative-1")))
            .rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL)
            .delivered((view, outcome) -> view.answered())
            .prepaid(prepaid(kit, accounts, group))
            .build(kit);
    }

    /** The ad's money on product items: the whole of it. */
    static Prepaid<CallContext> prepaid(RoutesphereKit kit, AccountRule accounts, int group) {
        return Prepaid.<CallContext>builder("ad")
            .admission(Admission.CHARGE_NOW)
            .accounts(accounts)
            .serviceGroup(view -> group)                                                    // §15.3 step 1b: the request's revenue stream
            .units(view -> isVideo(view) ? Set.of(AD_VIEW, AD_SEC) : Set.of(AD_VIEW))      // views of a product; a seconds bucket of before §15 may still pay a video
            .productKeys(view -> ProductKeys.exact(productOf(view)))                       // step 2's key: the product the view is
            .itemRate((view, tier, match) -> AD_SEC.equals(match.uom()) ? BigDecimal.valueOf(secondsOf(view)) : BigDecimal.ONE)   // one view of the product; a seconds bucket: its seconds
            .rate((view, tier, account) -> PackageMatch.CASH.equals(account.getUom()) ? new BigDecimal(PER_VIEW) : null)         // cash: the rate plan's price
            .build(kit);
    }

    static String productOf(CallContext view) {
        String called = called(view);
        return A_VIDEO_30.equals(called) ? VIDEO_30 : A_VIDEO_60.equals(called) ? VIDEO_60 : A_VIDEO_90.equals(called) ? VIDEO_90 : A_VIDEO_OF_101.equals(called) ? VIDEO_OF_101 : IMAGE;
    }

    static boolean isVideo(CallContext view) { return !IMAGE.equals(productOf(view)); }

    static int secondsOf(CallContext view) { return A_VIDEO_90.equals(called(view)) ? 90 : A_VIDEO_60.equals(called(view)) ? 60 : A_VIDEO_30.equals(called(view)) || A_VIDEO_OF_101.equals(called(view)) ? 30 : 0; }

    private static String called(CallContext view) { return ((CallFacts) view.facts).called(); }

    /** The call lane's keys would be the number's prefixes, longest first — the same library road. */
    static List<String> callKeys(String called) { return ProductKeys.prefixesOf(called); }
}
