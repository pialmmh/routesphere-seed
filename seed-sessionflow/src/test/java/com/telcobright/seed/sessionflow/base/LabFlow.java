package com.telcobright.seed.sessionflow.base;

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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The lab application on the base: decisions only (its fields are the lab's rules, final, shared by every session — never a session).
 * The payer is the partner the dial names, in the session's own root tree; every tier reserves one unit at its own rate; the plan is the
 * lab's carriers; the reroute table is the call's v1 table; the call's rules for the rest — answered = delivered, the call's refund rule.
 */
public class LabFlow extends RoutesphereFlow<LabContext> {

    /** The call's v1 table (C12): the next hop on these causes, the same hop on these. */
    public static final Map<String, RerouteAction> V1_TABLE = Map.of(
        "CALL_REJECTED", RerouteAction.REROUTE, "USER_BUSY", RerouteAction.REROUTE, "NO_ANSWER", RerouteAction.REROUTE,
        "SUBSCRIBER_ABSENT", RerouteAction.REROUTE, "RECOVERY_ON_TIMER_EXPIRE", RerouteAction.REROUTE,
        "NORMAL_TEMPORARY_FAILURE", RerouteAction.RETRY_SAME, "SWITCH_CONGESTION", RerouteAction.RETRY_SAME);

    final Map<String, String> ratePerTier;
    final List<LabHop> hops;
    final Set<String> unrated;
    final String name;

    public LabFlow(RoutesphereKit kit, Map<String, String> ratePerTier, List<LabHop> hops, Set<String> unrated) {
        this(kit, "LabSupervisor", ratePerTier, hops, unrated);
    }

    public LabFlow(RoutesphereKit kit, String name, Map<String, String> ratePerTier, List<LabHop> hops, Set<String> unrated) {
        super(kit);
        this.name = name;
        this.ratePerTier = Map.copyOf(ratePerTier);
        this.hops = List.copyOf(hops);
        this.unrated = Set.copyOf(unrated);
    }

    @Override public String name() { return name; }

    /** (a) The root by the door the dial came in at. */
    @Override protected String identifyTenant(RoutesphereFacts facts) { return byIngress(facts.ingress()); }

    @Override protected int serviceGroup(LabContext ctx) { return 0; }

    @Override
    protected String buildTask(LabContext ctx) {
        LabFacts facts = (LabFacts) ctx.facts;
        ctx.originatingCalledNumber = facts.called();
        ctx.callerIp = facts.ingress() == null ? null : facts.ingress().match();
        ctx.protocol = "LAB";
        return null;
    }

    @Override
    protected Tier identifyPartner(LabContext ctx, Candidate candidate) {
        return byPartnerId(ctx, ((LabFacts) ctx.facts).partnerId());
    }

    @Override
    protected TierRate rateAtLevel(LabContext ctx, Tier tier, Partner partner) {
        if (unrated.contains(tier.dbName())) return null;
        BigDecimal rate = new BigDecimal(ratePerTier.getOrDefault(tier.dbName(), "1.00"));
        return TierRate.of(rate, rate, "BDT", "880");
    }

    @Override protected RoutePlan routing(LabContext ctx) { return RoutePlan.of(hops); }

    @Override
    protected RerouteAction rerouteActionFor(String cause) { return V1_TABLE.getOrDefault(cause, RerouteAction.FAIL_TERMINAL); }

    @Override protected boolean delivered(LabContext ctx, Outcome outcome) { return ctx.answered(); }

    @Override protected RefundPolicy refundPolicy(LabContext ctx) { return RefundPolicy.callsRule(); }


    @Override
    protected Object buildSdr(LabContext ctx, Outcome outcome) { return "sdr:" + ctx.sessionId() + ":" + outcome + ":" + ctx.endCause; }
}
