package com.telcobright.seed.sessionflow.samples.wifi;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.statewalk.session.Outcome;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The WiFi session on the base (design §7 "WiFi session") — decisions only: the root by the gateway and its S-VLAN; the SUBSCRIBER as
 * the entry partner [assumes D5 — sample only: one partner row per MSISDN] inside that root; a window of one minute reserved at each
 * tier's per-minute rate, renewed by the budget child while the device is online; the site's gate as the one hop; no re-route; every
 * started minute charged at the end, the rest back; the session record (SDR) at the finish.
 */
public final class WifiFlow extends RoutesphereFlow<WifiContext> {

    private final Map<String, Integer> partnerBySubscriber;
    private final Map<String, BigDecimal> ratePerMinute;
    private final Map<String, WifiGate> gateByZone;

    /**
     * @param partnerBySubscriber the subscriber's partner by {@code <root>|<msisdn>}
     * @param ratePerMinute       a tier's partner's rate per minute online, by {@code <tier>#<partner>}
     * @param gateByZone          the gate of a site of a zone, by {@code <root>|<site>}
     */
    public WifiFlow(RoutesphereKit kit, Map<String, Integer> partnerBySubscriber, Map<String, BigDecimal> ratePerMinute, Map<String, WifiGate> gateByZone) {
        super(kit);
        this.partnerBySubscriber = Map.copyOf(partnerBySubscriber);
        this.ratePerMinute = Map.copyOf(ratePerMinute);
        this.gateByZone = Map.copyOf(gateByZone);
    }

    @Override public String name() { return "WifiSupervisor"; }

    /** (a) The root by the gateway and its S-VLAN. */
    @Override
    protected String identifyTenant(RoutesphereFacts facts) {
        WifiFacts f = (WifiFacts) facts;
        return byVlan(f.gateway(), f.svlan());
    }

    @Override protected int serviceGroup(WifiContext session) { return 0; }

    @Override
    protected String buildTask(WifiContext session) {
        WifiFacts f = (WifiFacts) session.facts;
        session.taskType = "WIFI";
        session.protocol = "RADIUS";
        session.msisdn = f.msisdn();
        session.mac = f.mac();
        session.zone = f.zone();
        session.originatingCallingNumber = f.msisdn();
        session.callerIp = f.gateway();
        return null;
    }

    /** [assumes D5] the subscriber is a partner of the root's tree. */
    @Override
    protected Tier identifyPartner(WifiContext session, Candidate candidate) {
        Integer partnerId = partnerBySubscriber.get(session.root + "|" + session.msisdn);
        return partnerId == null ? null : byPartnerId(session, partnerId);
    }

    /** One window: a minute at this tier's rate. */
    @Override
    protected TierRate rateAtLevel(WifiContext session, Tier tier, Partner partner) {
        BigDecimal perMinute = ratePerMinute.get(tier.dbName() + "#" + partner.getIdPartner());
        return perMinute == null ? null : TierRate.of(perMinute, perMinute, "BDT", "wifi");
    }

    @Override
    protected RoutePlan routing(WifiContext session) {
        WifiGate gate = gateByZone.get(session.root + "|" + ((WifiFacts) session.facts).site());
        return gate == null ? RoutePlan.none() : RoutePlan.one(gate);
    }

    /** The site's gate is the one hop: a refused gate ends the session, whatever the cause (design §7 (f): none). */
    @Override protected RerouteAction rerouteActionFor(String cause) { return RerouteAction.FAIL_TERMINAL; }

    /** Delivered = the gate opened (the session was answered); a session never online takes the call's refund rule (it pays nothing). */
    @Override protected boolean delivered(WifiContext session, Outcome outcome) { return session.answered(); }

    @Override protected RefundPolicy refundPolicy(WifiContext session) { return RefundPolicy.callsRule(); }


    /** The budget child's next window: one more minute. */
    @Override
    protected TierRate rateNextWindow(WifiContext session, LevelAdmission tier) {
        return TierRate.of(tier.getRate(), tier.getRate(), tier.getUom(), tier.getRatePrefix());
    }

    @Override
    protected BigDecimal billedUnits(WifiContext session) {
        return BigDecimal.valueOf(Math.max(1, (long) Math.ceil(session.durationSec / 60.0)));
    }

    @Override
    protected Object buildSdr(WifiContext session, Outcome outcome) {
        return new WifiSdr(session.sessionId(), session.root, session.msisdn, session.mac, session.zone, session.durationSec, outcome.name(), session.endCause);
    }
}
