package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.MoneyRules;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.Established;

import java.util.Map;

/**
 * The call lane as a BIG lane writes it (R5: a subclass of {@code RoutesphereFlow}), its money the call's Prepaid ({@link CallPrepaid},
 * given through {@link #moneyRules()}): the same answers as {@link CallLane}, no money move and no rating of its own.
 */
public final class CallFlowOnPrepaid extends RoutesphereFlow<CallContext> {

    private final Prepaid<CallContext> prepaid;
    private final Map<String, String> mnp;

    public CallFlowOnPrepaid(RoutesphereKit kit, CallRates rates, Map<String, String> mnp) {
        super(kit);
        this.prepaid = CallPrepaid.of(kit, rates);
        this.mnp = Map.copyOf(mnp);
    }

    @Override public String name() { return "CallSupervisor"; }
    @Override protected int serviceGroup(CallContext call) { return 0; }
    @Override protected String identifyRootLevelTenant(RoutesphereFacts facts) { return byIngress(facts.ingress()); }
    @Override protected String buildTask(CallContext call) { return CallLane.taskOf(call); }
    @Override protected Tier identifyPartner(CallContext call, Candidate candidate) { return byPartnerId(call, ((CallFacts) call.facts).partnerId()); }
    @Override protected Verdict applyRootRules(CallContext call, Tier root, Partner rootPartner) { return CallLane.digitFilter(call, root, rootPartner); }
    @Override protected RoutePlan route(CallContext call) { return RoutePlan.one(new ParityWire.Hop("carrier-a")); }
    @Override protected RerouteAction rerouteActionFor(String cause) { return RerouteAction.FAIL_TERMINAL; }
    @Override protected boolean delivered(CallContext call, Outcome outcome) { return call.answered(); }
    @Override protected RefundPolicy refundPolicy(CallContext call) { return RefundPolicy.callsRule(); }
    @Override protected void onAnswered(CallContext call, Established answer) { call.terminatingCalledNumber = mnp.get(call.originatingCalledNumber); }

    /** The Prepaid rates every tier: the base never asks this lane's own rate. */
    @Override
    protected TierRate rateAtLevel(CallContext call, Tier tier, Partner partner) {
        throw new IllegalStateException("the call's Prepaid rates every tier — rateAtLevel is not asked of a lane with a Prepaid");
    }

    @Override protected MoneyRules<CallContext> moneyRules() { return prepaid; }
}
