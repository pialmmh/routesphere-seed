package com.telcobright.seed.sessionflow.samples;

import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.SessionFlow;
import com.telcobright.seed.sessionflow.api.SessionFlowContext;
import com.telcobright.seed.sessionflow.api.SessionFlowTimings;
import com.telcobright.seed.sessionflow.api.SessionMachine;
import com.telcobright.seed.sessionflow.api.SessionState;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.EntryPartner;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.dependencies.SessionFlowKit;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.registry.InternalEventResolver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An ad view on the base — the shape ad-sphere takes. What is the ad's own: the call is inverted (the zone asks, the
 * rule and the campaigns answer, the advertiser is the partner that pays); several campaigns are candidates and the
 * first that every tier can pay plays; the house ad is free; a view is rated whole and reserved whole; the campaign's
 * quota is claimed last; "shown" is the answer; the CDR is service group 30 with the view's facts as meta data.
 */
public final class AdFlow extends SessionFlow<AdFlow.View> {

    public static final int SERVICE_GROUP_AD = 30;

    /** A campaign that may play for a rule: its route, the advertiser that pays, the price is the tier's own. */
    public record Campaign(int id, String route, Integer advertiserId, boolean houseAd) {}

    /** One ad view. */
    public static final class View extends SessionFlowContext {
        public volatile String zone;
        public volatile String gateway;
        public volatile String mac;
        public volatile String app;
        public volatile String ruleCode;
        public volatile List<Campaign> candidates = List.of();
        public volatile Campaign playing;
        public volatile boolean completed;
    }

    private final Map<String, String> ruleCodeByZone;
    private final Map<String, List<Campaign>> campaignsByRule;
    private final Map<String, BigDecimal> pricePerView;
    private final Map<Integer, AtomicInteger> quotaLeft = new ConcurrentHashMap<>();
    private final boolean unshownViewIsCharged;
    private final int operatorPartnerId;

    /**
     * @param pricePerView         the price of a view for a tier's partner, by {@code tenant#partner}
     * @param unshownViewIsCharged the owner's rule for an admitted view that never reached the screen: true = it keeps
     *                             its charge; false = the call's rule, it pays nothing
     */
    public AdFlow(SessionFlowKit kit, Map<String, String> ruleCodeByZone, Map<String, List<Campaign>> campaignsByRule,
                  Map<String, BigDecimal> pricePerView, boolean unshownViewIsCharged, int operatorPartnerId) {
        super(kit);
        this.ruleCodeByZone = ruleCodeByZone;
        this.campaignsByRule = campaignsByRule;
        this.pricePerView = pricePerView;
        this.unshownViewIsCharged = unshownViewIsCharged;
        this.operatorPartnerId = operatorPartnerId;
    }

    /** The views a campaign may still play. A campaign with no quota named plays without end. */
    public void quota(int campaignId, int views) { quotaLeft.put(campaignId, new AtomicInteger(views)); }

    @Override public String name() { return "ad"; }

    @Override protected int serviceGroup(View view) { return SERVICE_GROUP_AD; }

    /** A view has no ringing: "shown" is reported while the view window is open. */
    @Override
    public SessionFlowTimings timings() {
        SessionFlowTimings t = super.timings();
        return new SessionFlowTimings(t.preprocessingSec(), t.admittingSec(), t.admittedSec(), 0, t.activeMaxSec(), t.tearingDownSec());
    }

    /** The inversion: the subscriber is the caller, the matched rule's code is the called number, the gateway is the ingress. */
    @Override
    protected String buildTask(View view) {
        view.taskType = "AD";
        view.ruleCode = ruleCodeByZone.get(view.zone);
        if (view.ruleCode == null) return "NO_RULE";
        view.originatingCallingNumber = view.mac;
        view.originatingCalledNumber = view.ruleCode;
        view.callerIp = view.gateway;
        view.outgoingRoute = view.zone;
        return null;
    }

    /** The campaigns of the rule, in the dialplan's order; the house ad last. */
    @Override
    protected String selectCandidates(View view) {
        view.candidates = List.copyOf(campaignsByRule.getOrDefault(view.ruleCode, List.of()));
        return view.candidates.isEmpty() ? "NO_RUNNABLE_CAMPAIGN" : null;
    }

    @Override protected int candidateCount(View view) { return view.candidates.size(); }

    /** After a ledger fault no paying campaign is tried any more: only the house ad may still play. */
    @Override
    protected boolean useCandidate(View view, int index) {
        Campaign campaign = view.candidates.get(index);
        if (view.systemFault != null && !campaign.houseAd()) return false;
        view.playing = campaign;
        view.incomingRoute = campaign.route();
        return true;
    }

    @Override protected boolean isFree(View view) { return view.playing.houseAd(); }

    /** The advertiser of the candidate pays. The house ad of a tenant with no advertiser has no partner and no tier. */
    @Override
    protected EntryPartner identifyEntryPartner(View view) {
        return view.playing.advertiserId() == null ? null : entryOfPartner(view, view.playing.advertiserId());
    }

    /** A view is rated whole and reserved whole. */
    @Override
    protected TierRate rateAtLevel(View view, Tenant tier, Partner partner, int levelIndex) {
        BigDecimal price = pricePerView.get(tier.getDbName() + "#" + partner.getIdPartner());
        return price == null ? null : TierRate.of(price, price, "BDT", view.ruleCode).usage("video", 15);
    }

    /** The campaign's quota is claimed last, when every tier has reserved. A dry run claims nothing. */
    @Override
    protected String confirmAdmission(View view, StepMode mode) {
        if (mode == StepMode.SIMULATE) return null;
        AtomicInteger left = quotaLeft.get(view.playing.id());
        if (left != null && left.getAndUpdate(n -> n > 0 ? n - 1 : 0) <= 0) return "QUOTA_EXHAUSTED";
        view.task = claimedTask(view);
        return null;
    }

    /** The claim's record: the campaign_task row of this view, open (B2: the base closes it at the end). */
    private CampaignTask claimedTask(View view) {
        String tenant = view.entryTenant != null ? view.entryTenant.getDbName() : view.tenantName;
        int payer = view.partner != null ? view.partner.getIdPartner() : 0;
        return new CampaignTask(view.sessionKey, tenant, view.playing.id(), payer, CampaignKind.AD, view.mac, "creative-" + view.playing.id(),
            view.zone, null, null, TaskState.PROCESSING, Instant.ofEpochMilli(kit.clock().millis()), null, null, 0, null, TaskCharge.FREE, Map.of());
    }

    /** A ledger fault first; then the budget that ran out before every campaign was tried; else nobody could pay. */
    @Override
    protected String rejectCause(View view) {
        if (view.systemFault != null) return SessionCause.BILLING_SYSTEM_ERROR;
        return view.budgetSpent ? SessionCause.ADMISSION_TIMEOUT : "NO_FUNDED_CAMPAIGN";
    }

    @Override
    public void defineRoutes(InternalEventResolver routes) {
        routes.forwardTo(Wire.TYPE, Wire.Ring.class);
        routes.forwardTo(Wire.TYPE, Wire.Answer.class);
        routes.forwardTo(Wire.TYPE, Wire.Hangup.class);
    }

    @Override
    protected void startSignaling(View view, SessionMachine machine) { machine.spawnChild(Wire.TYPE, new Wire.Leg(view)); }

    /** "Shown" is the ad's answer: the CDR's answer time. It comes as a progress report, before the view completes. */
    @Override
    protected void onProgress(View view, String phase) {
        if ("SHOWN".equals(phase) && view.answeredAtMs == 0) view.answeredAtMs = kit.clock().millis();
    }

    /** The view completed. */
    @Override
    protected void onAnswered(View view, Object grant) { view.completed = true; }

    /** A shown view pays what it reserved. An admitted view never shown pays by the owner's rule. */
    @Override
    protected BigDecimal chargeAtSettle(View view, LevelAdmission level) {
        return view.answered() || unshownViewIsCharged ? TierSettlement.reservedOf(level) : BigDecimal.ZERO;
    }

    @Override
    protected String timeoutCause(String state) {
        return SessionState.ADMITTED.equals(state) ? "NOT_SHOWN" : super.timeoutCause(state);
    }

    @Override protected Integer payerWhenUnknown(View view, Tenant tenant) { return operatorPartnerId; }

    @Override
    protected void fillCdr(View view, LevelAdmission level, CdrEvent cdr) {
        cdr.channelReadCodecName = "video";
        cdr.meta.put("zone", view.zone);
        cdr.meta.put("app", view.app);
        cdr.meta.put("mac", view.mac);
        cdr.meta.put("completed", view.completed);
        if (view.playing != null) {
            cdr.meta.put("campaignId", view.playing.id());
            cdr.meta.put("fallback", view.playing.houseAd());
        }
    }

    /** The campaigns of a rule, as a test writes them. */
    public static List<Campaign> campaigns(Campaign... inOrder) { return new ArrayList<>(List.of(inOrder)); }
}
