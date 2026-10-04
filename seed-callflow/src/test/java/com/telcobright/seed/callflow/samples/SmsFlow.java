package com.telcobright.seed.callflow.samples;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.CallCause;
import com.telcobright.seed.callflow.api.CallFlow;
import com.telcobright.seed.callflow.api.CallFlowContext;
import com.telcobright.seed.callflow.api.CallFlowTimings;
import com.telcobright.seed.callflow.api.CallMachine;
import com.telcobright.seed.callflow.api.EntryPartner;
import com.telcobright.seed.callflow.api.TierRate;
import com.telcobright.seed.callflow.dependencies.CallFlowKit;
import com.telcobright.statewalk.registry.InternalEventResolver;
import com.telcobright.statewalk.session.events.ServiceEnd;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * An SMS on the base — the shape the SMS switch takes when it moves onto it. What is the SMS's own: the partner is found
 * by the user name; the rate is per part and admission reserves every part; a failed submit is tried again on the next
 * route; a delivered message is a call that is answered and ends at once.
 */
public final class SmsFlow extends CallFlow<SmsFlow.Message> {

    /** One message. */
    public static final class Message extends CallFlowContext {
        public volatile String user;
        public volatile String sender;
        public volatile String receiver;
        public volatile String text;
        public volatile int parts;
        public volatile int routeIndex;
    }

    private final Map<String, Integer> partnerByUser;
    private final Map<String, BigDecimal> ratePerPart;
    private final Map<String, String> unitOfTier;
    private final List<String> routes;

    /**
     * @param ratePerPart the rate per message part of a tier's partner, by {@code tenant#partner}
     * @param unitOfTier  the unit a tier's partner pays in, by {@code tenant#partner} (absent = money)
     * @param routes      the SMS routes of the root tenant, in failover order
     */
    public SmsFlow(CallFlowKit kit, Map<String, Integer> partnerByUser, Map<String, BigDecimal> ratePerPart, Map<String, String> unitOfTier,
                   List<String> routes) {
        super(kit);
        this.partnerByUser = partnerByUser;
        this.ratePerPart = ratePerPart;
        this.unitOfTier = unitOfTier;
        this.routes = routes;
    }

    @Override public String name() { return "sms"; }

    @Override protected int serviceGroup(Message sms) { return 0; }

    /** A message has no ringing: one window from the submit to the delivery. */
    @Override
    public CallFlowTimings timings() {
        CallFlowTimings t = super.timings();
        return new CallFlowTimings(t.preprocessingSec(), t.admittingSec(), t.admittedSec(), 0, t.activeMaxSec(), t.tearingDownSec());
    }

    @Override
    protected String buildTask(Message sms) {
        if (sms.text == null || sms.text.isEmpty()) return "EMPTY_MESSAGE";
        sms.taskType = "SMS";
        sms.parts = (sms.text.length() + 159) / 160;
        sms.originatingCallingNumber = sms.sender;
        sms.originatingCalledNumber = sms.receiver;
        return null;
    }

    @Override
    protected EntryPartner identifyEntryPartner(Message sms) {
        Integer partnerId = partnerByUser.get(sms.user);
        return partnerId == null ? null : entryOfPartner(sms, partnerId);
    }

    /** Every part is reserved at admission: a message is rated before it is sent. */
    @Override
    protected TierRate rateAtLevel(Message sms, Tenant tier, Partner partner, int levelIndex) {
        String key = tier.getDbName() + "#" + partner.getIdPartner();
        BigDecimal perPart = ratePerPart.get(key);
        if (perPart == null) return null;
        return TierRate.of(perPart.multiply(BigDecimal.valueOf(sms.parts)), perPart, unitOfTier.getOrDefault(key, "BDT"), "880");
    }

    @Override
    protected String resolveRoute(Message sms, Tenant root) {
        if (routes.isEmpty()) return CallCause.NO_ROUTE;
        sms.routeIndex = 0;
        sms.outgoingRoute = routes.get(0);
        return null;
    }

    @Override
    public void defineRoutes(InternalEventResolver routesOfEvents) {
        routesOfEvents.forwardTo(Wire.TYPE, Wire.Answer.class);
        routesOfEvents.forwardTo(Wire.TYPE, Wire.Fail.class);
    }

    @Override
    protected void startSignaling(Message sms, CallMachine machine) { machine.spawnChild(Wire.TYPE, new Wire.Leg(sms)); }

    /** A failed submit goes to the next route, while there is one. */
    @Override
    protected boolean nextAttempt(Message sms, String failureCause) {
        if (sms.routeIndex + 1 >= routes.size()) return false;
        sms.routeIndex++;
        sms.outgoingRoute = routes.get(sms.routeIndex);
        return true;
    }

    /** Delivered: a message has no duration, the call ends at once. */
    @Override
    protected void onActive(Message sms, CallMachine machine) { machine.publish(new ServiceEnd(CallCause.NORMAL_CLEARING)); }
}
