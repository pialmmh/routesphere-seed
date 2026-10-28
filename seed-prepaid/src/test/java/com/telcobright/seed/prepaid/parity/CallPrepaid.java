package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.prepaid.api.Admission;
import com.telcobright.seed.prepaid.api.Final;
import com.telcobright.seed.prepaid.api.PackageMatch;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

/**
 * The call's Prepaid — design §10.1's call-service builder, as an application writes it: windowed (the kit's cadence: the live call's
 * 60 s, the first renewal at 58 s), one unit per window at the call's rate on the account tried ({@code CallRates}: a package minute =
 * 1.0, cash = the plan's rate of a minute, abroad the root's 117 rate), cash only abroad with the live's {@code NO_BALANCE_INT_OUT}, and
 * at the end the live's re-rated cost per tier ({@code CallRates.cost} on the terminating number, six decimals) or everything back.
 * Everything else — the account order, the hold, the renewal with the remainder, the settlement both ways, the release — is the seed's.
 */
public final class CallPrepaid {

    private CallPrepaid() {}

    public static Prepaid<CallContext> of(RoutesphereKit kit, CallRates rates) {
        return Prepaid.<CallContext>builder("call")
            .admission(Admission.HOLD_WINDOW)
            .window(Duration.ofSeconds(kit.settings().reservePeriodSec()))
            .firstRenewal(Duration.ofSeconds(kit.settings().firstRenewalSec()))
            .rate((call, tier, account) -> rates.ratePerMinute(call, tier.tier(), tier.partner(), account))
            .units(call -> CallRates.isIntlOut(call) ? Set.of() : PackageMatch.CALL_AND_SMS_UNITS)   // C3: abroad, no package pays — cash only
            .noBalanceCause((call, tier) -> CallRates.isIntlOut(call) ? CallLane.NO_BALANCE_INT_OUT : SessionCause.INSUFFICIENT_BALANCE)
            .actualCharge((call, tier) -> costAtTheEnd(rates, call, tier))          // answered: holds back, the actual charge taken; else every hold back
            .build(kit);
    }

    /** {@code BalanceBillingService.finalizeBalance} 127–148: the rate re-looked-up on the terminating number, then the talk time's cost. */
    static BigDecimal costAtTheEnd(CallRates rates, CallContext call, LevelAdmission tier) {
        BigDecimal rate = rates.rateAtTheEnd(call, tier.getDbName(), tier.getPartnerId(), tier.getUom());
        return rates.cost(call, tier.getUom(), rate);
    }
}
