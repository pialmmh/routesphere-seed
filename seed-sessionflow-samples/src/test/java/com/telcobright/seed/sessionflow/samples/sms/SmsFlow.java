package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.Lookups;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereApp;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SettleOutcome;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy.Rule;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The SMS on the base, written with the builder (R5: "big lanes subclass; small lanes use the builder") — decisions only, each a small
 * lambda or a method of this class: the root by the door (the HTTP listener or the Kafka topic), the entry partner by the user inside that
 * root, every segment reserved at each tier's per-segment rate, the root's SMSCs in failover order, a refused submit failed over by cause;
 * the receipt decides the money (design §5 and §7, the owner's rule of 2026-10-16: refund optional by config per lane): delivered pays its
 * reserve, an accepted message that was not delivered takes the lane's refund rule, a message no SMSC accepted gives every reserve back.
 */
public final class SmsFlow {

    private final Map<String, Integer> partnerByUser;
    private final Map<String, BigDecimal> ratePerSegment;
    private final Map<String, List<Smsc>> smscsByRoot;
    private final boolean refundOnNonDelivery;

    private SmsFlow(Map<String, Integer> partnerByUser, Map<String, BigDecimal> ratePerSegment, Map<String, List<Smsc>> smscsByRoot,
                    boolean refundOnNonDelivery) {
        this.partnerByUser = Map.copyOf(partnerByUser);
        this.ratePerSegment = Map.copyOf(ratePerSegment);
        this.smscsByRoot = Map.copyOf(smscsByRoot);
        this.refundOnNonDelivery = refundOnNonDelivery;
    }

    /**
     * The SMS lane on this kit.
     *
     * @param partnerByUser        the entry partner by {@code <root>|<user>}
     * @param ratePerSegment       a tier's partner's rate per segment, by {@code <tier>#<partner>}
     * @param smscsByRoot          each root's SMSCs, in failover order
     * @param refundOnNonDelivery  the lane's config: an accepted message that was not delivered is refunded (true) or charged (false)
     */
    public static RoutesphereFlow<SmsContext> of(RoutesphereKit kit, Map<String, Integer> partnerByUser, Map<String, BigDecimal> ratePerSegment,
                                                Map<String, List<Smsc>> smscsByRoot, boolean refundOnNonDelivery) {
        SmsFlow sms = new SmsFlow(partnerByUser, ratePerSegment, smscsByRoot, refundOnNonDelivery);
        return RoutesphereApp.<SmsContext>builder("SmsSupervisor")
            .serviceGroup(0)
            .identifyRootLevelTenant(SmsFlow::rootByTheDoor)
            .identifyPartner((m, candidate, find) -> find.byPartnerId(m, sms.partnerByUser.get(m.root + "|" + m.user)))
            .rateAtLevel(sms::everySegment)
            .route(sms::theRootsSmscs)
            .delivered((m, outcome) -> SmsSubmit.DELIVERED.equals(m.endCause))
            .refundPolicy(m -> RefundPolicy.of(Rule.REFUND, sms.refundOnNonDelivery ? Rule.REFUND : Rule.CHARGE))
            .rerouteActionFor(SmsFlow::nextSmscWhenCongested)
            .buildTask(SmsFlow::taskOf)
            .settleOutcome(SmsFlow::whatTheReceiptSaid)
            .build(kit);
    }

    /** (a) The Kafka topic the message came from, else the HTTP listener. */
    static String rootByTheDoor(RoutesphereFacts facts, Lookups find) {
        SmsFacts f = (SmsFacts) facts;
        return Ingress.TOPIC.equals(f.ingressKind()) ? find.byTopic(f.ingressMatch()) : find.byListener(f.ingressMatch());
    }

    /** The message → its task: the user, the numbers, the segments; an empty message is refused. */
    static String taskOf(SmsContext m) {
        SmsFacts f = (SmsFacts) m.facts;
        if (f.segments() <= 0) return "EMPTY_MESSAGE";
        m.taskType = "SMS";
        m.protocol = "SMPP";
        m.user = f.user();
        m.segments = f.segments();
        m.originatingCallingNumber = f.sender();
        m.originatingCalledNumber = f.receiver();
        m.task = CampaignTask.minted(m.sessionId(), m.root, CampaignKind.SMS, f.receiver(), null, null, f.user(),
            Instant.ofEpochMilli(f.atMs()), Map.of()).processing();             // the campaign_task row; the base closes it at the finish
        return null;
    }

    /** (e) Every segment, reserved at admission at this tier's per-segment rate: a message is rated before it is sent. */
    TierRate everySegment(SmsContext m, Tier tier, Partner partner) {
        BigDecimal perSegment = ratePerSegment.get(tier.dbName() + "#" + partner.getIdPartner());
        return perSegment == null ? null : TierRate.of(perSegment.multiply(BigDecimal.valueOf(m.segments)), perSegment, "BDT", "880");
    }

    /** (d) The root's SMSCs in failover order. */
    RoutePlan theRootsSmscs(SmsContext m) {
        List<Smsc> smscs = smscsByRoot.getOrDefault(m.root, List.of());
        if (!smscs.isEmpty()) m.outgoingRoute = smscs.get(0).smscId();
        return smscs.isEmpty() ? RoutePlan.none() : RoutePlan.of(smscs);
    }

    /** (f) A congested SMSC hands the message to the next; any other refusal ends it. */
    static RerouteAction nextSmscWhenCongested(String cause) {
        return switch (cause == null ? "" : cause) {
            case "THROTTLED", "QUEUE_FULL", "SMSC_SYSTEM_ERROR" -> RerouteAction.REROUTE;
            default -> RerouteAction.FAIL_TERMINAL;
        };
    }

    /** The receipt said delivered → consumed; accepted and not delivered → offered, not consumed; never accepted → failed. */
    static SettleOutcome whatTheReceiptSaid(SmsContext m) {
        if (SmsSubmit.DELIVERED.equals(m.endCause)) return SettleOutcome.CONSUMED;
        return m.answered() ? SettleOutcome.OFFERED_NOT_CONSUMED : SettleOutcome.FAILED;
    }
}
