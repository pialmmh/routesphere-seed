package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.Ingress;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The SMS on the base (design §5 and §7, the owner's rule of 2026-10-16: refund optional by config per lane) — decisions only: the
 * root by the door (the HTTP listener or the Kafka topic), the entry partner by the user inside that root, every segment reserved at
 * each tier's per-segment rate, the root's SMSCs in failover order, a refused submit failed over by cause; the receipt decides the
 * money: delivered pays its reserve, an accepted message that was not delivered takes the lane's refund rule, a message no SMSC
 * accepted gives every reserve back.
 */
public final class SmsFlow extends RoutesphereFlow<SmsContext> {

    private final Map<String, Integer> partnerByUser;
    private final Map<String, BigDecimal> ratePerSegment;
    private final Map<String, List<Smsc>> smscsByRoot;
    private final boolean refundOnNonDelivery;

    /**
     * @param partnerByUser        the entry partner by {@code <root>|<user>}
     * @param ratePerSegment       a tier's partner's rate per segment, by {@code <tier>#<partner>}
     * @param smscsByRoot          each root's SMSCs, in failover order
     * @param refundOnNonDelivery  the lane's config: an accepted message that was not delivered is refunded (true) or charged (false)
     */
    public SmsFlow(RoutesphereKit kit, Map<String, Integer> partnerByUser, Map<String, BigDecimal> ratePerSegment, Map<String, List<Smsc>> smscsByRoot,
                   boolean refundOnNonDelivery) {
        super(kit);
        this.partnerByUser = Map.copyOf(partnerByUser);
        this.ratePerSegment = Map.copyOf(ratePerSegment);
        this.smscsByRoot = Map.copyOf(smscsByRoot);
        this.refundOnNonDelivery = refundOnNonDelivery;
    }

    @Override public String name() { return "SmsSupervisor"; }

    /** (a) The root by the door: the Kafka topic the message came from, else the HTTP listener. */
    @Override
    protected String identifyTenant(RoutesphereFacts facts) {
        SmsFacts f = (SmsFacts) facts;
        return Ingress.TOPIC.equals(f.ingressKind()) ? byTopic(f.ingressMatch()) : byListener(f.ingressMatch());
    }

    @Override protected int serviceGroup(SmsContext sms) { return 0; }

    @Override
    protected String buildTask(SmsContext sms) {
        SmsFacts f = (SmsFacts) sms.facts;
        if (f.segments() <= 0) return "EMPTY_MESSAGE";
        sms.taskType = "SMS";
        sms.protocol = "SMPP";
        sms.user = f.user();
        sms.segments = f.segments();
        sms.originatingCallingNumber = f.sender();
        sms.originatingCalledNumber = f.receiver();
        sms.task = CampaignTask.minted(sms.sessionId(), sms.root, CampaignKind.SMS, f.receiver(), null, null, f.user(),
            Instant.ofEpochMilli(f.atMs()), Map.of()).processing();             // the campaign_task row; the base closes it at the finish
        return null;
    }

    @Override
    protected Tier identifyPartner(SmsContext sms, Candidate candidate) {
        Integer partnerId = partnerByUser.get(sms.root + "|" + sms.user);
        return partnerId == null ? null : byPartnerId(sms, partnerId);
    }

    /** Every segment, reserved at admission at this tier's per-segment rate: a message is rated before it is sent. */
    @Override
    protected TierRate rateAtLevel(SmsContext sms, Tier tier, Partner partner) {
        BigDecimal perSegment = ratePerSegment.get(tier.dbName() + "#" + partner.getIdPartner());
        return perSegment == null ? null : TierRate.of(perSegment.multiply(BigDecimal.valueOf(sms.segments)), perSegment, "BDT", "880");
    }

    /** The root's SMSCs in failover order. */
    @Override
    protected RoutePlan routing(SmsContext sms) {
        List<Smsc> smscs = smscsByRoot.getOrDefault(sms.root, List.of());
        if (!smscs.isEmpty()) sms.outgoingRoute = smscs.get(0).smscId();
        return smscs.isEmpty() ? RoutePlan.none() : RoutePlan.of(smscs);
    }

    /** A congested SMSC hands the message to the next; any other refusal ends it. */
    @Override
    protected RerouteAction rerouteActionFor(String cause) {
        return switch (cause == null ? "" : cause) {
            case "THROTTLED", "QUEUE_FULL", "SMSC_SYSTEM_ERROR" -> RerouteAction.REROUTE;
            default -> RerouteAction.FAIL_TERMINAL;
        };
    }

    /** The receipt said delivered. */
    @Override protected boolean delivered(SmsContext sms, Outcome outcome) { return SmsSubmit.DELIVERED.equals(sms.endCause); }

    @Override
    protected SettleOutcome settleOutcome(SmsContext sms) {
        if (SmsSubmit.DELIVERED.equals(sms.endCause)) return SettleOutcome.CONSUMED;
        return sms.answered() ? SettleOutcome.OFFERED_NOT_CONSUMED : SettleOutcome.FAILED;
    }

    @Override protected RefundPolicy refundPolicy(SmsContext sms) { return RefundPolicy.of(Rule.REFUND, refundOnNonDelivery ? Rule.REFUND : Rule.CHARGE); }

}
