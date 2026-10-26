package com.telcobright.seed.prepaid.parity;

import com.telcobright.seed.prepaid.api.Admission;
import com.telcobright.seed.prepaid.api.Final;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereApp;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.switchledger.api.AccountOrder;

import java.math.BigDecimal;

/**
 * The SMS-shaped lane of item 4 (design §10.1's sms-service builder, as an application writes it): CHARGE_NOW — every tier charged at
 * once at admission, no hold row; a message pays one {@code OTH_ea} unit or {@value #PER_MESSAGE} in cash ({@code TF_min} talk minutes never
 * pay a message); delivered → the charge stands; failed → given back on every tier, or kept, by the lane's refund rule. On the parity
 * scene's tree (CallContext is the session's context: the SMS needs nothing of its own here).
 */
public final class SmsLane {

    public static final String DELIVERED = "DELIVERED";
    public static final String PER_MESSAGE = "0.25";

    private SmsLane() {}

    public static RoutesphereFlow<CallContext> onPrepaid(RoutesphereKit kit, boolean refundOnFailure) {
        return RoutesphereApp.<CallContext>builder("SmsSupervisor")
            .serviceGroup(0)
            .identifyRootLevelTenant((facts, find) -> find.byIngress(facts.ingress()))
            .identifyPartner((sms, candidate, find) -> find.byPartnerId(sms, ((CallFacts) sms.facts).partnerId()))
            .buildTask(SmsLane::taskOf)
            .applyRootRules(CallLane::digitFilter)
            .route(sms -> RoutePlan.one(new ParityWire.Hop("smsc-a")))
            .rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL)
            .delivered((sms, outcome) -> DELIVERED.equals(sms.endCause))
            .prepaid(prepaid(kit, refundOnFailure))
            .build(kit);
    }

    static Prepaid<CallContext> prepaid(RoutesphereKit kit, boolean refundOnFailure) {
        return Prepaid.<CallContext>builder("sms")
            .admission(Admission.CHARGE_NOW)
            .rate((sms, tier, account) -> AccountOrder.OTH_EA.equals(account.getUom()) ? BigDecimal.ONE : new BigDecimal(PER_MESSAGE))
            .chooseAccounts((sms, tier, fundable) -> fundable.stream().filter(a -> !AccountOrder.TF_MIN.equals(a.getUom())).toList())
            .finalize(sms -> DELIVERED.equals(sms.endCause) ? Final.nothing() : refundOnFailure ? Final.refundAll() : Final.nothing())
            .build(kit);
    }

    static String taskOf(CallContext sms) {
        sms.taskType = "SMS";
        sms.protocol = "SMPP";
        sms.originatingCalledNumber = ((CallFacts) sms.facts).called();
        return null;
    }
}
