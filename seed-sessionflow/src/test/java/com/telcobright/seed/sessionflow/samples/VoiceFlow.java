package com.telcobright.seed.sessionflow.samples;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.SessionFlow;
import com.telcobright.seed.sessionflow.api.SessionFlowContext;
import com.telcobright.seed.sessionflow.api.SessionMachine;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.EntryPartner;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.dependencies.SessionFlowKit;
import com.telcobright.statewalk.registry.InternalEventResolver;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A voice call on the base — the shape the call switch takes when it moves onto it. What is the call's own:
 * the partner is found by the source address; the rate is per minute and admission reserves one minute; the root
 * tenant filters the digits and resolves the route by the dialed prefix — every route that serves it, in order, as the
 * call's route plan; a failed attempt follows the switch's v1 table (busy → the next hop, a temporary failure → the same
 * hop again); the settlement charges the minutes talked; a long call renews its reserve every period.
 */
public class VoiceFlow extends SessionFlow<VoiceFlow.Call> {

    /** One voice call. */
    public static final class Call extends SessionFlowContext {
        public volatile String sourceIp;
        public volatile String dialed;
        public volatile String caller;
        public volatile boolean legsKilled;

        @SuppressWarnings("unchecked")
        public RoutePlan<Route> plan() { return (RoutePlan<Route>) routePlan; }
    }

    /** A route of the root tenant: the dialed prefix it serves, its name, its supplier. */
    public record Route(String prefix, String name, int supplierId) {}

    private final Map<String, Integer> partnerBySourceIp;
    private final Map<String, BigDecimal> ratePerMinute;
    private final Iterable<Route> routes;

    /**
     * @param partnerBySourceIp the entry partner of a source address
     * @param ratePerMinute     the rate per minute of a tier's partner, by {@code tenant#partner}
     */
    public VoiceFlow(SessionFlowKit kit, Map<String, Integer> partnerBySourceIp, Map<String, BigDecimal> ratePerMinute, Iterable<Route> routes) {
        super(kit);
        this.partnerBySourceIp = partnerBySourceIp;
        this.ratePerMinute = ratePerMinute;
        this.routes = routes;
    }

    @Override public String name() { return "voice"; }

    /** Billing detects a voice call's service group itself. */
    @Override protected int serviceGroup(Call call) { return 0; }

    @Override
    protected String buildTask(Call call) {
        if (call.dialed == null || call.dialed.isBlank()) return "INVALID_NUMBER";
        call.taskType = "VOICE";
        call.protocol = "ESL";
        call.originatingCallingNumber = call.caller;
        call.originatingCalledNumber = call.dialed;
        call.callerIp = call.sourceIp;
        return null;
    }

    @Override
    protected EntryPartner identifyEntryPartner(Call call) {
        Integer partnerId = partnerBySourceIp.get(call.sourceIp);
        return partnerId == null ? null : entryOfPartner(call, partnerId);
    }

    /** One minute of talk time is reserved at admission. */
    @Override
    protected TierRate rateAtLevel(Call call, Tenant tier, Partner partner, int levelIndex) {
        BigDecimal perMinute = ratePerMinute.get(tier.getDbName() + "#" + partner.getIdPartner());
        return perMinute == null ? null : TierRate.of(perMinute, perMinute, "BDT", call.dialed.substring(0, 3));
    }

    /** The digit filter of the root tenant: no calls abroad; the national number goes out with the country code. */
    @Override
    protected String applyRootRules(Call call, Tenant root, Partner rootPartner) {
        if (call.dialed.startsWith("00")) return "DIGIT_FILTER_DENIED";
        call.terminatingCalledNumber = "88" + call.dialed;
        return null;
    }

    /** Every route of the root that serves the dialed prefix, in order, is a hop; the first is tried first. */
    @Override
    protected String resolveRoute(Call call, Tenant root) {
        List<Route> hops = new ArrayList<>();
        for (Route route : routes) if (call.dialed.startsWith(route.prefix())) hops.add(route);
        if (hops.isEmpty()) return SessionCause.NO_ROUTE;
        call.routePlan = RoutePlan.of(hops);
        takeHop(call, hops.get(0));
        return null;
    }

    private static void takeHop(Call call, Route hop) {
        call.outgoingRoute = hop.name();
        call.outPartnerId = hop.supplierId();
    }

    /** The call switch's v1 table (C12): busy and the like go to the next hop, a temporary failure tries the same hop again. */
    @Override
    protected RerouteAction rerouteActionFor(String protocol, String cause) {
        if (cause == null) return RerouteAction.FAIL_TERMINAL;
        return switch (cause) {
            case "CALL_REJECTED", "USER_BUSY", "NO_ANSWER", "SUBSCRIBER_ABSENT", "RECOVERY_ON_TIMER_EXPIRE" -> RerouteAction.REROUTE;
            case "NORMAL_TEMPORARY_FAILURE", "SWITCH_CONGESTION" -> RerouteAction.RETRY_SAME;
            default -> RerouteAction.FAIL_TERMINAL;
        };
    }

    @Override
    public void defineRoutes(InternalEventResolver routes) {
        routes.forwardTo(Wire.TYPE, Wire.Ring.class);
        routes.forwardTo(Wire.TYPE, Wire.Answer.class);
        routes.forwardTo(Wire.TYPE, Wire.Fail.class);
        routes.forwardTo(Wire.TYPE, Wire.Hangup.class);
        routes.forwardTo(Wire.TYPE, Wire.Defer.class);
    }

    /** One leg on the wire, over the hop the plan points at (the next one after a re-route). */
    @Override
    protected void startSignaling(Call call, SessionMachine machine) {
        if (call.plan() != null) takeHop(call, call.plan().current());
        machine.spawnChild(Wire.TYPE, new Wire.Leg(call));
    }

    /** Every started minute is charged; an unanswered call pays nothing. */
    @Override
    protected BigDecimal chargeAtSettle(Call call, LevelAdmission level) {
        if (!call.answered()) return BigDecimal.ZERO;
        long minutes = Math.max(1, (long) Math.ceil(call.durationSec / 60.0));
        return level.getRate().multiply(BigDecimal.valueOf(minutes));
    }

    /** The call switch's shape: a balance child holds the tiers, renews them while the call runs and settles them when it ends. */
    @Override
    protected boolean settlesAsync() { return true; }

    /** A long call reserves one more minute every period. */
    @Override
    protected TierRate rateNextWindow(Call call, LevelAdmission level) {
        return TierRate.of(level.getRate(), level.getRate(), level.getUom(), level.getRatePrefix());
    }

    /** A call succeeded when it was answered and ended by a hangup — not when it was cut. */
    @Override
    protected boolean succeeded(Call call) { return call.answered() && SessionCause.NORMAL_CLEARING.equals(call.endCause); }

    /** Both legs are hung up on every end path. */
    @Override
    protected void onTeardown(Call call, SessionMachine machine) { call.legsKilled = true; }

    @Override
    protected void fillCdr(Call call, LevelAdmission level, CdrEvent cdr) { cdr.channelReadCodecName = "PCMA"; }
}
