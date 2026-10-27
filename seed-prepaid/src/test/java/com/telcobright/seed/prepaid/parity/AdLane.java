package com.telcobright.seed.prepaid.parity;

import com.telcobright.seed.prepaid.api.Admission;
import com.telcobright.seed.prepaid.api.PackageMatch;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereApp;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;

import java.math.BigDecimal;
import java.util.Set;

/**
 * L3 · The ad-shaped lane, as the owner described it on 2026-10-27: an ad view is ONE call, charged at admission on every tier like an
 * SMS ("for ad we charge now, like sms, no refunds/adjustments are required") — so no {@code finalize}: the charge stands. The packages
 * pay first, the best match first ("package has higher priority, best package to match. if no packages are matched, use rateplan based
 * rate matching"): an IMAGE view is one {@code AD_view} unit, a VIDEO its seconds of {@code AD_sec}; no matching package → cash at the
 * rate-plan price ({@value #PER_VIEW} here). On the parity scene's tree (CallContext is the session's context: the called number says
 * image or video).
 */
public final class AdLane {

    public static final String AD_VIEW = "AD_view";
    public static final String AD_SEC = "AD_sec";
    public static final String PER_VIEW = "0.40";
    public static final int VIDEO_SECONDS = 15;
    /** The called number of a VIDEO view; any other is an image. */
    public static final String VIDEO = "8801700000015";

    private AdLane() {}

    public static RoutesphereFlow<CallContext> onPrepaid(RoutesphereKit kit) {
        return RoutesphereApp.<CallContext>builder("AdSupervisor")
            .serviceGroup(30)
            .identifyRootLevelTenant((facts, find) -> find.byIngress(facts.ingress()))
            .identifyPartner((view, candidate, find) -> find.byPartnerId(view, ((CallFacts) view.facts).partnerId()))
            .buildTask(AdLane::taskOf)
            .route(view -> RoutePlan.one(new ParityWire.Hop("creative-1")))
            .rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL)
            .delivered((view, outcome) -> view.answered())
            .prepaid(prepaid(kit))
            .build(kit);
    }

    /** The ad's whole money code — the owner's builder of 2026-10-27, nothing more. */
    static Prepaid<CallContext> prepaid(RoutesphereKit kit) {
        return Prepaid.<CallContext>builder("ad")
            .admission(Admission.CHARGE_NOW)
            .packageUnits(view -> isVideo(view) ? Set.of(AD_SEC) : Set.of(AD_VIEW))
            .rate((view, tier, account) -> switch (account.getUom()) {
                case AD_VIEW -> BigDecimal.ONE;                                   // one view off the image package
                case AD_SEC -> BigDecimal.valueOf(VIDEO_SECONDS);                 // the video's seconds off the video package
                case PackageMatch.CASH -> new BigDecimal(PER_VIEW);               // no package matched: the rate plan's price
                default -> null;
            })
            .build(kit);
    }

    static boolean isVideo(CallContext view) { return VIDEO.equals(((CallFacts) view.facts).called()); }

    static String taskOf(CallContext view) {
        view.taskType = "AD";
        view.protocol = "HTTP";
        view.originatingCalledNumber = ((CallFacts) view.facts).called();
        return null;
    }
}
