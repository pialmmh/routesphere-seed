package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.statewalk.session.Verdict;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The ESL call on the base (P1 §H; design §7 "ESL call") — decisions only: no state, no ESL word (that is {@link EslCall}). What is the
 * call's own: the entry partner by the source address, INSIDE the root its FreeSWITCH connection named (C1); one minute at each tier's
 * per-minute rate reserved at admission (C6) and renewed every period while it talks (C14); the root's digit filter (C5); the routes
 * of the root by the dialed prefix, in order, as the plan (C8); the call switch's v1 re-route table (C12); every started minute
 * charged at the end, the rest back (C17).
 */
public final class VoiceFlow extends RoutesphereFlow<VoiceContext> {

    /** A route of a root's dialplan: the dialed prefix it serves and the ESL target it transfers to (its destination is filled per call). */
    public record DialplanRoute(String prefix, EslTarget target) {}

    private final Map<String, Integer> partnerBySource;
    private final Map<String, BigDecimal> ratePerMinute;
    private final Map<String, List<DialplanRoute>> routesByRoot;

    /**
     * @param partnerBySource the entry partner by {@code <root>|<source ip>} (C1: inside the root, never across)
     * @param ratePerMinute   a tier's partner's rate per minute, by {@code <tier>#<partner>}
     * @param routesByRoot    each root's dialplan, in the order it tries the routes
     */
    public VoiceFlow(RoutesphereKit kit, Map<String, Integer> partnerBySource, Map<String, BigDecimal> ratePerMinute,
                     Map<String, List<DialplanRoute>> routesByRoot) {
        super(kit);
        this.partnerBySource = Map.copyOf(partnerBySource);
        this.ratePerMinute = Map.copyOf(ratePerMinute);
        this.routesByRoot = Map.copyOf(routesByRoot);
    }

    @Override public String name() { return "CallSupervisor"; }

    /** Billing detects a voice call's service group itself. */
    @Override protected int serviceGroup(VoiceContext call) { return 0; }

    @Override
    protected String buildTask(VoiceContext call) {
        EslFacts f = facts(call);
        if (f.called() == null || f.called().isBlank()) return "INVALID_NUMBER";
        call.taskType = "VOICE";
        call.protocol = "ESL";
        call.originatingCallingNumber = f.caller();
        call.originatingCalledNumber = f.called();
        call.callerIp = f.sourceIp();
        return null;
    }

    /** C1: the partner by the source address, found in the call's OWN root's tree; none → PARTNER_NOT_FOUND. */
    @Override
    protected Tier identifyEntryPartner(VoiceContext call, Candidate candidate) {
        Integer partnerId = partnerBySource.get(call.root + "|" + facts(call).sourceIp());
        return partnerId == null ? null : entryOfPartner(call, partnerId);
    }

    /** C6: ONE minute at this tier's own per-minute rate. */
    @Override
    protected TierRate rateAtLevel(VoiceContext call, Tier tier, Partner partner) {
        BigDecimal perMinute = ratePerMinute.get(tier.dbName() + "#" + partner.getIdPartner());
        return perMinute == null ? null : TierRate.of(perMinute, perMinute, "BDT", prefixOf(call));
    }

    /** C5: the root's digit filter, before the root's reserve — no calls abroad; the national number goes out with the country code. */
    @Override
    protected Verdict applyRootRules(VoiceContext call, Tier root, Partner rootPartner) {
        if (call.originatingCalledNumber.startsWith("00")) return Verdict.reject("DIGIT_FILTER_DENIED");
        call.terminatingCalledNumber = "88" + call.originatingCalledNumber;
        return Verdict.ok();
    }

    /** C8: every route of the ROOT that serves the dialed prefix, in order, transferring the digit-filtered number. */
    @Override
    protected RoutePlan routing(VoiceContext call) {
        List<EslTarget> hops = new ArrayList<>();
        for (DialplanRoute r : routesByRoot.getOrDefault(call.root, List.of())) {
            if (!call.originatingCalledNumber.startsWith(r.prefix())) continue;
            EslTarget t = r.target();
            hops.add(new EslTarget(t.routeName(), t.supplierId(), t.targetIp(), t.sofiaProfile(), call.billingCalledNumber(), t.context()));
        }
        if (!hops.isEmpty()) call.outgoingRoute = hops.get(0).routeName();
        return hops.isEmpty() ? RoutePlan.none() : RoutePlan.of(hops);
    }

    /** C12: the call switch's v1 table. */
    @Override
    protected RerouteAction rerouteActionFor(String cause) {
        if (cause == null) return RerouteAction.FAIL_TERMINAL;
        return switch (cause) {
            case "CALL_REJECTED", "USER_BUSY", "NO_ANSWER", "SUBSCRIBER_ABSENT", "RECOVERY_ON_TIMER_EXPIRE" -> RerouteAction.REROUTE;
            case "NORMAL_TEMPORARY_FAILURE", "SWITCH_CONGESTION" -> RerouteAction.RETRY_SAME;
            default -> RerouteAction.FAIL_TERMINAL;
        };
    }

    /** C13: the answer — the recording starts here (the root's switch; always on in the sample). */
    @Override protected void onServiceStarted(VoiceContext call) { call.recordingStarted = true; }

    /** C14: every period one more minute at the tier's rate. */
    @Override
    protected TierRate rateNextWindow(VoiceContext call, LevelAdmission tier) {
        return TierRate.of(tier.getRate(), tier.getRate(), tier.getUom(), tier.getRatePrefix());
    }

    /** C17: every started minute of the answered talk time; an unanswered call is not delivered (the refund rule: nothing). */
    @Override
    protected BigDecimal billedUnits(VoiceContext call) {
        return BigDecimal.valueOf(Math.max(1, (long) Math.ceil(call.durationSec / 60.0)));
    }

    @Override
    protected void fillCdr(VoiceContext call, LevelAdmission tier, CdrEvent cdr) {
        cdr.channelReadCodecName = call.answer == null ? null : call.answer.codec();
    }

    private static EslFacts facts(VoiceContext call) { return (EslFacts) call.facts; }

    private static String prefixOf(VoiceContext call) {
        String n = call.originatingCalledNumber;
        return n == null || n.length() < 3 ? n : n.substring(0, 3);
    }
}
