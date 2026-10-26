package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.sessionflow.api.RerouteAction;
import com.telcobright.seed.sessionflow.api.RoutePlan;
import com.telcobright.seed.sessionflow.api.RoutesphereApp;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.statewalk.session.Verdict;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The call-shaped lane of the parity suite, written with {@link RoutesphereApp} as a small lane is (R5) and as the live call is shaped:
 * windowed (the kit's settings: 60 s, the first renewal at 58 s), package minutes and cash, international cash-only at the root's 117 rate,
 * the default rate 0.40, the root's digit filter, the ported (MNP) number learned at the answer. The lane writes NO money move: its
 * lambdas answer the base's questions (which account, what rate, how many minutes), and the base holds, renews, settles and releases.
 *
 * <p>The live call's cause when no account the partner owns can fund the call ({@code DefaultAdmissionService} 284–286): a rating lambda
 * refuses in the ledger's words ({@link LedgerRefusal}: {@code NO_BALANCE_INT_OUT} abroad, {@code INSUFFICIENT_BALANCE} at home).
 */
public final class CallLane {

    public static final String NO_BALANCE_INT_OUT = "NO_BALANCE_INT_OUT";
    public static final String DIGIT_FILTER_DENIED = "DIGIT_FILTER_DENIED";
    /** The root's digit filter of the scene: a called number starting with this is denied at the root tier. */
    public static final String DENIED_PREFIX = "99";

    private CallLane() {}

    /**
     * The lane on the base alone: the seven forced answers and the call's hooks.
     *
     * @param mnp the ported numbers the switch learns at the answer: dialed → terminating
     */
    public static RoutesphereFlow<CallContext> onTheBase(RoutesphereKit kit, CallRates rates, Map<String, String> mnp, Clock clock) {
        return RoutesphereApp.<CallContext>builder("CallSupervisor")
            .serviceGroup(0)
            .identifyRootLevelTenant((facts, find) -> find.byIngress(facts.ingress()))
            .identifyPartner((call, candidate, find) -> find.byPartnerId(call, ((CallFacts) call.facts).partnerId()))
            .buildTask(CallLane::taskOf)
            .rateAtLevel((call, tier, partner) -> firstMinute(rates, clock, call, tier, partner))
            .applyRootRules(CallLane::digitFilter)
            .route(call -> RoutePlan.one(new ParityWire.Hop("carrier-a")))
            .rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL)
            .delivered((call, outcome) -> call.answered())
            .refundPolicy(call -> RefundPolicy.callsRule())
            .onAnswered((call, answer) -> call.terminatingCalledNumber = mnp.get(call.originatingCalledNumber))
            .rateNextWindow(CallLane::oneMoreMinute)
            .billedUnits(rates::billedMinutes)
            .build(kit);
    }

    /**
     * L2a 3 · The SAME lane in builder syntax with its money as the call's Prepaid ({@link CallPrepaid}, design §10.1): no rateAtLevel, no
     * refundPolicy, no rateNextWindow, no billedUnits — the Prepaid answers them; the lane keeps its own answers (who pays, where it goes,
     * the digit filter, delivered, the ported number at the answer).
     */
    public static RoutesphereFlow<CallContext> onPrepaid(RoutesphereKit kit, CallRates rates, Map<String, String> mnp) {
        return RoutesphereApp.<CallContext>builder("CallSupervisor")
            .serviceGroup(0)
            .identifyRootLevelTenant((facts, find) -> find.byIngress(facts.ingress()))
            .identifyPartner((call, candidate, find) -> find.byPartnerId(call, ((CallFacts) call.facts).partnerId()))
            .buildTask(CallLane::taskOf)
            .applyRootRules(CallLane::digitFilter)
            .route(call -> RoutePlan.one(new ParityWire.Hop("carrier-a")))
            .rerouteActionFor(cause -> RerouteAction.FAIL_TERMINAL)
            .delivered((call, outcome) -> call.answered())
            .onAnswered((call, answer) -> call.terminatingCalledNumber = mnp.get(call.originatingCalledNumber))
            .prepaid(CallPrepaid.of(kit, rates))
            .build(kit);
    }

    static String taskOf(CallContext call) {
        call.taskType = "VOICE";
        call.protocol = "ESL";
        call.originatingCalledNumber = ((CallFacts) call.facts).called();
        return null;
    }

    /**
     * ONE minute at this tier ({@code ReserveBalanceStep} 82–133): the accounts that may pay, best first; the base holds on the FIRST of them
     * (the live tries each until one pays — P2); the rate of a minute on it; refused in the live's words when none can pay.
     */
    static TierRate firstMinute(CallRates rates, Clock clock, CallContext call, Tier tier, Partner partner) {
        List<PackageAccount> accounts = rates.fundable(call, tier, partner, LocalDateTime.now(clock));
        if (accounts.isEmpty()) {
            throw new LedgerRefusal(CallRates.isIntlOut(call) ? NO_BALANCE_INT_OUT : SessionCause.INSUFFICIENT_BALANCE,
                "nothing partner " + partner.getIdPartner() + " owns at " + tier.dbName() + " can fund this call");
        }
        PackageAccount account = accounts.get(0);
        BigDecimal rate = rates.ratePerMinute(call, tier, partner, account);
        return TierRate.of(rate, rate, account.getUom(), CallRates.prefixOf(call)).onAccount(account);
    }

    /** The root's digit filter ({@code DefaultAdmissionService} 270): a denied prefix refuses the call after the lower tiers held. */
    static Verdict digitFilter(CallContext call, Tier root, Partner rootPartner) {
        return call.originatingCalledNumber.startsWith(DENIED_PREFIX) ? Verdict.reject(DIGIT_FILTER_DENIED) : Verdict.ok();
    }

    /** C14: every period one more minute at the tier's own rate ({@code BalanceBillingService.reserveNextMinute} 49–70). */
    static TierRate oneMoreMinute(CallContext call, LevelAdmission tier) {
        return TierRate.of(tier.getRate(), tier.getRate(), tier.getUom(), tier.getRatePrefix());
    }
}
