package com.telcobright.seed.memledgerremote;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.prepaid.api.Admission;
import com.telcobright.seed.prepaid.api.PackageMatch;
import com.telcobright.seed.prepaid.api.PayingTier;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.IdentificationRule;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.MoneyRules;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Route;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.SessionStart;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * The ad's shape at the seed's own seam (ad-sphere's AdFlow is not in this repository): a view's candidates are campaigns in order, the
 * FREE house ad last; every paid one is charged at admission through a Prepaid (CHARGE_NOW, one {@code AD_view} or cash) — the account
 * walk reads the partner's accounts from the ledger first. And AdFlow's rule, as ad-sphere {@code recall-db-client} ef48dcec writes it
 * ({@code AdFlow.checkPartner} :388-391 and {@code whyNotTried} :411-412): after a LEDGER FAULT only the house ad is tried — read from
 * the admission's {@code systemFault}.
 */
final class AdShapedFlow extends RoutesphereFlow<AdShapedFlow.View> {

    /** The door of the views: the rule {@code vlan gw-1:310 → btcl}. */
    static final Ingress DOOR = Ingress.vlan("gw-1", 310);
    static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    /** The free house ad (its payer the operator's house partner 1) and a view's candidates: three paid campaigns, then the house ad. */
    static final Campaign HOUSE_AD = new Campaign(6, 1, true);
    static final List<Candidate> THREE_PAID_THEN_THE_HOUSE_AD =
        List.of(new Campaign(1, 501, false), new Campaign(2, 502, false), new Campaign(3, 503, false), HOUSE_AD);

    static final class View extends RoutesphereContext {}

    record ViewFacts(String sessionId, long atMs, Ingress ingress) implements RoutesphereFacts {}

    /** A campaign paid by {@code payer}; the house ad is free. */
    record Campaign(int id, int payer, boolean free) implements Candidate {}

    record Creative(String id) implements Route {}

    private final List<Candidate> campaigns;
    private final Prepaid<View> prepaid;

    AdShapedFlow(RoutesphereKit kit, List<Candidate> campaigns) {
        super(kit);
        this.campaigns = List.copyOf(campaigns);
        this.prepaid = Prepaid.<View>builder("ad")
            .admission(Admission.CHARGE_NOW)
            .units(view -> Set.of("AD_view"))
            .rate(AdShapedFlow::perView)
            .build(kit);
    }

    /** INIT + ADMITTING through the handler alone: the root by the door, the campaigns, the walk. */
    Verdict admitAView(View view, String id) {
        view.facts = new ViewFacts(id, System.currentTimeMillis(), DOOR);
        onStart(view, new SessionStart(view.facts));
        view.candidates = campaigns;
        return admit(view);
    }

    @Override public String name() { return "AdShapedSupervisor"; }
    @Override protected int serviceGroup(View view) { return 30; }
    @Override protected String identifyRootLevelTenant(RoutesphereFacts facts) { return byIngress(facts.ingress()); }
    @Override protected List<Candidate> selectCandidates(View view) { return campaigns; }
    @Override protected Tier identifyPartner(View view, Candidate candidate) { return byPartnerId(view, ((Campaign) candidate).payer()); }

    /** AdFlow's rule: after a ledger fault only the house ad is tried; then the base's own check. */
    @Override
    protected Verdict checkPartner(View view, Tier tier, Partner partner) {
        if (tier.isLeaf() && view.systemFault != null && !isFree(view, view.candidateBeingAdmitted())) return Verdict.reject(SessionCause.BILLING_SYSTEM_ERROR);
        return super.checkPartner(view, tier, partner);
    }

    @Override
    protected TierRate rateAtLevel(View view, Tier tier, Partner partner) {
        throw new IllegalStateException("the ad's Prepaid rates every tier — rateAtLevel is not asked of a lane with a Prepaid");
    }

    @Override protected RoutePlan route(View view) { return RoutePlan.one(new Creative("the campaign's creative")); }
    @Override protected RerouteAction rerouteActionFor(String cause) { return RerouteAction.FAIL_TERMINAL; }
    @Override protected boolean delivered(View view, Outcome outcome) { return view.answered(); }
    @Override protected RefundPolicy refundPolicy(View view) { return RefundPolicy.callsRule(); }
    @Override protected MoneyRules<View> moneyRules() { return prepaid; }

    private static BigDecimal perView(View view, PayingTier tier, PackageAccount account) {
        return PackageMatch.CASH.equals(account.getUom()) ? new BigDecimal("0.40") : BigDecimal.ONE;
    }

    // ── the tests' world ────────────────────────────────────────────────────

    /** The operator btcl (the house ad's payer 1, the reseller 5) and its reseller's three advertisers, over {@code port}. */
    static RoutesphereKit kitOver(LedgerPort port) {
        Tenant tree = new TenantTreeBuilder()
            .root("btcl").partner(1, "house", PartnerType.CUSTOMER).partner(5, "Res5", PartnerType.RESELLER).and()
            .tenant("btcl_res_5", "btcl").partner(501, "advertiser 1", PartnerType.CUSTOMER).partner(502, "advertiser 2", PartnerType.CUSTOMER)
                .partner(503, "advertiser 3", PartnerType.CUSTOMER).and()
            .build();
        return RoutesphereKit.builder().tenants(TenantLookup.of(tree)).identification(List.of(new IdentificationRule("vlan", "gw-1:310", "btcl")))
            .ledger(port).cdr(new RecordingCdrSink()).zone(DHAKA).clock(Clock.system(DHAKA)).settings(RoutesphereSettings.defaults()).build();
    }

    /** The chain's note of each refused candidate ({@code AdmissionChain.undo}): "candidate N refused: CAUSE". */
    static List<String> refusalsOf(View view) {
        return view.history.snapshot().stream().filter(r -> r.isNote() && r.cause().matches("(?s).*candidate \\d+ refused: .*"))
            .map(r -> r.cause().substring(r.cause().indexOf("candidate")).trim()).toList();
    }
}
