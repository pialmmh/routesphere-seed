package com.telcobright.seed.switchledger.samples;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.switchledger.api.AccountOrder;
import com.telcobright.seed.switchledger.api.LiveBalance;
import com.telcobright.seed.switchledger.api.WindowRenewal;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Route;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.SessionStart;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A session on the switch ledger, the shape a WiFi switch takes on the base (RoutesphereFlow): the partner is found by the device's MAC in
 * the session's own root tree; a tier's rate is per window; the leaf tier's account is the first unit BUCKET of its partner
 * ({@link AccountOrder#buckets}: no package, no wifi) and the base reserves it through the kit's ledger (the {@code MemLedgerPort}); a tier
 * with no rate is free. A long session renews the same window on the same account — through the base's own renewal, or, given the
 * ledger's live-balance peek, through {@link WindowRenewal}: the whole window, else the remainder (the call's C14). The settlement charges
 * what the test scripts.
 */
public class LedgerFlow extends RoutesphereFlow<LedgerFlow.Session> {

    /** The WiFi gateway's door: the rule {@code vlan gw-1:310 → btcl}. */
    public static final Ingress GATEWAY = Ingress.vlan("gw-1", 310);

    public static final class Session extends RoutesphereContext {
        public volatile String mac;
    }

    public record Facts(String sessionId, long atMs, Ingress ingress, String mac) implements RoutesphereFacts {}

    record Gate(String id) implements Route {}

    private final Map<String, Integer> partnerByMac;
    private final Map<String, BigDecimal> ratePerWindow;
    private final LiveBalance balances;
    /** What a tier finally pays at the settlement: by default everything it reserved. */
    public volatile Function<LevelAdmission, BigDecimal> charge = TierSettlement::reservedOf;

    public LedgerFlow(RoutesphereKit kit, Map<String, Integer> partnerByMac, Map<String, BigDecimal> ratePerWindow) { this(kit, partnerByMac, ratePerWindow, null); }

    public LedgerFlow(RoutesphereKit kit, Map<String, Integer> partnerByMac, Map<String, BigDecimal> ratePerWindow, LiveBalance balances) {
        super(kit);
        this.partnerByMac = partnerByMac;
        this.ratePerWindow = ratePerWindow;
        this.balances = balances;
    }

    @Override public String name() { return "WifiLikeSupervisor"; }

    /** (a) The root by the gateway's door ({@code vlan gw-1:310}). */
    @Override protected String identifyRootLevelTenant(RoutesphereFacts facts) { return byIngress(facts.ingress()); }

    @Override protected int serviceGroup(Session session) { return 0; }

    @Override
    protected String buildTask(Session session) {
        session.mac = ((Facts) session.facts).mac();
        session.taskType = "WIFI";
        session.protocol = "RADIUS";
        session.originatingCallingNumber = session.mac;
        return null;
    }

    @Override
    protected Tier identifyPartner(Session session, Candidate candidate) {
        Integer partnerId = partnerByMac.get(session.mac);
        return partnerId == null ? null : byPartnerId(session, partnerId);
    }

    /** The tier's rate on its partner's first unit bucket; no rate = a free tier; a rate with no bucket = unrated (no package, no wifi). */
    @Override
    protected TierRate rateAtLevel(Session session, Tier tier, Partner partner) {
        BigDecimal rate = ratePerWindow.get(tier.dbName() + "#" + partner.getIdPartner());
        if (rate == null) return TierRate.free();
        List<PackageAccount> buckets = AccountOrder.buckets(partner, tier.tenant().getContext(), LocalDateTime.now(kit.clock()));
        if (buckets.isEmpty()) return null;
        PackageAccount account = buckets.get(0);
        return TierRate.of(rate, rate, account.getUom(), "wifi").onAccount(account);
    }

    @Override protected RoutePlan route(Session session) { return RoutePlan.one(new Gate("gw-1")); }

    /** The gate is the one hop: no re-route, whatever the cause. */
    @Override protected RerouteAction rerouteActionFor(String cause) { return RerouteAction.FAIL_TERMINAL; }

    /** The call's rules: delivered = answered; not delivered pays nothing. */
    @Override protected boolean delivered(Session session, Outcome outcome) { return session.answered(); }

    @Override protected RefundPolicy refundPolicy(Session session) { return RefundPolicy.callsRule(); }


    /** The same window again, on the tier's own account. */
    @Override
    protected TierRate rateNextWindow(Session session, LevelAdmission level) {
        return TierRate.of(level.getRate(), level.getRate(), level.getUom(), level.getRatePrefix());
    }

    /** The WiFi's renewal (3b): the whole window through the chain's own reserve, else the remainder's seconds, under the base's window reference. */
    @Override
    protected double renewWindowSeconds(Session session, LevelAdmission level) {
        if (balances == null || level.getDebitReference() == null) return super.renewWindowSeconds(session, level);
        WindowRenewal renewal = new WindowRenewal(balances, (tier, amount, reference) -> reserveWindow(session, tier, amount, reference));
        String reference = level.getDebitReference() + "#W" + (level.getReservationCount() + 1);
        return renewal.renewSeconds(level, level.getRate(), reference, level.getRate(), kit.settings().reservePeriodSec());
    }

    @Override protected BigDecimal chargeAtSettle(Session session, LevelAdmission level) { return charge.apply(level); }

    /** A session at the gateway's door: the base's (a) finds btcl by the vlan rule. */
    public static Session session(String id, String mac) {
        Session s = new Session();
        s.facts = new Facts(id, System.currentTimeMillis(), GATEWAY, mac);
        return s;
    }

    /** INIT + ADMITTING through the handler alone (no registry): the root, the task, the one candidate, the walk. */
    public Verdict admit(Session session, boolean answered) {
        onStart(session, new SessionStart(session.facts));
        session.candidates = List.of(Candidate.SELF);
        Verdict verdict = admit(session);
        if (answered) session.answer = new Established(System.currentTimeMillis(), "gw-1", null, null);
        return verdict;
    }
}
