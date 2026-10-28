package com.telcobright.seed.sessionflow.samples.prepaid;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.prepaid.api.AccountRule;
import com.telcobright.seed.prepaid.api.Admission;
import com.telcobright.seed.prepaid.api.Final;
import com.telcobright.seed.prepaid.api.PackageMatch;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.samples.sms.SmsContext;
import com.telcobright.seed.sessionflow.samples.voice.VoiceContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * L2a · The call-shaped and the SMS-shaped Prepaid of design §10.1, as an application writes them (samples: the money is the seed's; the
 * application writes only its rates, its account choice and its end). The values come from the application's own config (R0): here the
 * kit's cadence and a refund switch.
 *
 * <pre>
 *   call   HOLD_WINDOW · one unit per window at the tier's rate · cash only abroad (NO_BALANCE_INT_OUT) · answered → the talk time's cost,
 *          else every hold back
 *   sms    CHARGE_NOW · every tier charged per message × segments at once, no hold row · delivered → the charge stands; failed → given back
 *          on every tier, or kept, by the config
 * </pre>
 */
public final class PrepaidBuilders {

    public static final String NO_BALANCE_INT_OUT = "NO_BALANCE_INT_OUT";
    /** The samples' rates: a talk minute in cash, a message in cash (a package unit pays one of its own). */
    public static final BigDecimal CASH_PER_MINUTE = new BigDecimal("0.50"), CASH_PER_MESSAGE = new BigDecimal("0.25");

    private PrepaidBuilders() {}

    /** design §10.1 · call-service. */
    public static Prepaid<VoiceContext> call(RoutesphereKit kit) {
        return Prepaid.<VoiceContext>builder("call")
            .admission(Admission.HOLD_WINDOW)
            .window(Duration.ofSeconds(kit.settings().reservePeriodSec()))
            .firstRenewal(Duration.ofSeconds(kit.settings().firstRenewalSec()))
            .rate((call, tier, account) -> unitRate(account, CASH_PER_MINUTE))
            .units(call -> intlOut(call) ? Set.of() : PackageMatch.CALL_AND_SMS_UNITS)        // abroad: no package pays — cash only
            .noBalanceCause((call, tier) -> intlOut(call) ? NO_BALANCE_INT_OUT : SessionCause.INSUFFICIENT_BALANCE)
            .finalize(call -> call.answered() ? Final.charge(tier -> talkTimeCost(call, tier)) : Final.releaseAll())
            .build(kit);
    }

    /** design §10.1 · sms-service; {@code refundOnFailure} is the lane's config. */
    public static Prepaid<SmsContext> sms(RoutesphereKit kit, boolean refundOnFailure) {
        return Prepaid.<SmsContext>builder("sms")
            .admission(Admission.CHARGE_NOW)
            .rate((sms, tier, account) -> unitRate(account, CASH_PER_MESSAGE).multiply(BigDecimal.valueOf(Math.max(1, sms.segments))))
            .accounts(AccountRule.UNITS_THEN_CASH)
            .units(sms -> Set.of("OTH_ea"))                                                       // a message's package, never the call's minutes
            .finalize(sms -> "DELIVERED".equals(sms.endCause) ? Final.nothing() : refundOnFailure ? Final.refundAll() : Final.nothing())
            .build(kit);
    }

    /** One unit on this account: a package unit is one of its own; cash pays the plan's price. */
    static BigDecimal unitRate(PackageAccount account, BigDecimal cash) { return "BDT".equals(account.getUom()) ? cash : BigDecimal.ONE; }

    /** The talk time's cost at the end: package minutes = seconds / 60; cash = rate / 60 × seconds (six decimals, the live's). */
    static BigDecimal talkTimeCost(VoiceContext call, LevelAdmission tier) {
        BigDecimal seconds = BigDecimal.valueOf(call.durationSec);
        BigDecimal perMinute = "BDT".equals(tier.getUom()) ? tier.getRate() : BigDecimal.ONE;
        return perMinute.multiply(seconds).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
    }

    static boolean intlOut(VoiceContext call) {
        String n = call.billingCalledNumber();
        return n != null && (n.startsWith("00") || n.startsWith("+"));
    }
}
