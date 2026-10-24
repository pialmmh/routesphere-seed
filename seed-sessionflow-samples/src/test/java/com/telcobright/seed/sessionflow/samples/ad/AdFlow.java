package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SettleOutcome;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy.Rule;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.SignalingProgress;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The ad view on the base (design §6: "AdFlow — decisions only"): the root by the listener the request came in on (a); the zone's
 * ad rule; the candidates — the rule's campaigns × their contents, the free house ad last; the payer is the CANDIDATE's advertiser,
 * found in its tier of the view's own tree (c); the whole view reserved at each tier's per-view rate (e); the quota claimed after every
 * tier reserved; the chosen creative is the one hop, never a second one after the hand-over (d, f); shown → each tier pays its reserve,
 * handed over but never shown → the unshown rule by config, never handed over → nothing (e).
 */
public final class AdFlow extends RoutesphereFlow<AdContext> {

    /** A campaign of a rule: its content and its advertiser (null for the house ad). */
    public record Campaign(int campaignId, String creativeId, String mediaKind, int seconds, Integer advertiserId, boolean house) {}

    private final Map<String, String> ruleByZone;
    private final Map<String, List<Campaign>> campaignsByRule;
    private final Map<String, BigDecimal> ratePerView;
    private final boolean unshownCharged;
    private final AdQuota quota;

    /**
     * @param ruleByZone      the ad rule of a zone, by {@code <root>|<zone>}
     * @param campaignsByRule the campaigns of a rule in the order tried, by {@code <root>|<rule>}
     * @param ratePerView     a tier's partner's rate per view, by {@code <tier>#<partner>}
     * @param unshownCharged  the lane's config {@code unshown}: a view handed over and never shown is charged (the 2026-10-04 rule) or refunded
     */
    public AdFlow(RoutesphereKit kit, Map<String, String> ruleByZone, Map<String, List<Campaign>> campaignsByRule, Map<String, BigDecimal> ratePerView,
                  boolean unshownCharged, AdQuota quota) {
        super(kit);
        this.ruleByZone = Map.copyOf(ruleByZone);
        this.campaignsByRule = Map.copyOf(campaignsByRule);
        this.ratePerView = Map.copyOf(ratePerView);
        this.unshownCharged = unshownCharged;
        this.quota = quota;
    }

    @Override public String name() { return "AdSupervisor"; }

    /** (a) The root by the HTTP listener the request came in on. */
    @Override protected String identifyRootLevelTenant(RoutesphereFacts facts) { return byListener(((AdFacts) facts).listen()); }

    /** An ad view is group 30, taken as given. */
    @Override protected int serviceGroup(AdContext view) { return 30; }

    /** The zone's rule is the view's "called number"; a zone no rule serves is refused. */
    @Override
    protected String buildTask(AdContext view) {
        AdFacts f = (AdFacts) view.facts;
        String rule = ruleByZone.get(view.root + "|" + f.zone());
        if (rule == null) return "NO_RULE";
        view.ruleCode = rule;
        view.taskType = "AD";
        view.protocol = "HTTP";
        view.originatingCallingNumber = f.msisdn();
        view.originatingCalledNumber = rule;
        view.callerIp = f.gateway();
        view.task = CampaignTask.minted(view.sessionId(), view.root, CampaignKind.AD, f.msisdn(), f.zone(), f.site(), f.app(),
            Instant.ofEpochMilli(f.atMs()), Map.of()).processing();             // layer 1's row, handed to the core; the base closes it
        return null;
    }

    /** The rule's campaigns in order, then the free house ad. */
    @Override
    protected List<Candidate> selectCandidates(AdContext view) {
        List<Candidate> out = new ArrayList<>();
        for (Campaign c : campaignsByRule.getOrDefault(view.root + "|" + view.ruleCode, List.of())) {
            out.add(new AdCandidate(c.campaignId(), c.creativeId(), c.mediaKind(), c.seconds(), c.advertiserId(), c.house()));
        }
        return out;
    }

    /** The inversion: the payer is the candidate's advertiser, found in its tier of THIS view's tree; the house ad has none. */
    @Override
    protected Tier identifyPartner(AdContext view, Candidate candidate) {
        AdCandidate k = (AdCandidate) candidate;
        return k.house() || k.payerPartnerId() == null ? null : byPartnerId(view, k.payerPartnerId());
    }

    /** The whole view at this tier's per-view rate. */
    @Override
    protected TierRate rateAtLevel(AdContext view, Tier tier, Partner partner) {
        BigDecimal perView = ratePerView.get(tier.dbName() + "#" + partner.getIdPartner());
        return perView == null ? null : TierRate.of(perView, perView, "BDT", view.ruleCode);
    }

    /** The last gate: one view of the campaign's quota, after every tier reserved (a refusal gives every reserve back). */
    @Override
    protected Verdict confirmAdmission(AdContext view, Candidate candidate) {
        AdCandidate k = (AdCandidate) candidate;
        return k.house() || quota.claim(k.campaignId()) ? Verdict.ok() : Verdict.reject("QUOTA_GONE");
    }

    /** The creative of the candidate under admission is the one hop (step (d) runs inside the candidate's walk, before {@code chosen}). */
    @Override
    protected RoutePlan route(AdContext view) {
        AdCandidate k = (AdCandidate) view.candidateBeingAdmitted();
        return k == null ? RoutePlan.none() : RoutePlan.one(k.creative());
    }

    /** The creative is the one hop: never a second creative after the hand-over, whatever the cause. */
    @Override protected RerouteAction rerouteActionFor(String cause) { return RerouteAction.FAIL_TERMINAL; }

    /** AdPlayback's progress: the hand-over (OFFERED), the beacons, the whole view (VIEWED) — each with the seconds watched so far. */
    @Override
    protected void onSignalingProgress(AdContext view, SignalingProgress progress) {
        if (AdPlayback.OFFERED.equals(progress.phase())) view.handedOver = true;
        if (AdPlayback.VIEWED.equals(progress.phase())) view.viewed = true;
        int watched = AdPlayback.watchedSecondsOf(progress);
        if (watched >= 0) view.watchedSeconds = watched;
    }

    /** The answer is /shown (the owner's ruling): the creative on the screen — the view is delivered. */
    @Override protected void onAnswered(AdContext view, Established answer) { view.shown = true; }

    /** A view's task completes when it was watched to its end (/viewed), not when it was only shown. */
    @Override protected boolean taskCompleted(AdContext view, Outcome outcome) { return outcome == Outcome.SUCCEEDED && view.viewed; }

    @Override protected boolean delivered(AdContext view, Outcome outcome) { return view.shown; }

    @Override
    protected SettleOutcome settleOutcome(AdContext view) {
        return !view.handedOver ? SettleOutcome.FAILED : view.shown ? SettleOutcome.CONSUMED : SettleOutcome.OFFERED_NOT_CONSUMED;
    }

    @Override protected RefundPolicy refundPolicy(AdContext view) { return RefundPolicy.of(Rule.REFUND, unshownCharged ? Rule.CHARGE : Rule.REFUND); }


    @Override
    protected Object buildSdr(AdContext view, Outcome outcome) {
        AdCandidate k = view.chosen instanceof AdCandidate c ? c : null;
        return new AdSdr(view.sessionId(), view.root, k == null ? 0 : k.campaignId(), k == null ? null : k.creativeId(), view.handedOver, view.shown,
            view.watchedSeconds, outcome.name(), view.endCause);
    }
}
