package com.telcobright.seed.prepaid.parity;

import com.telcobright.rtc.domainmodel.IntlOutRating;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.mysqlentity.Rate;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.switchledger.api.AccountOrder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The live call's rate and cost code, moved as it is into the parity lane (design §10.4: "CallRates = today's rate and charge code moved
 * as is") — the lane's OWN part of the money, never a money move:
 * <ul>
 *   <li>the rate of one minute at a tier ({@code ReserveBalanceStep} 82–93): a package minute ({@code TF_min}) = 1.0; cash ({@code BDT}) =
 *       the partner's plan by the longest prefix of the called number, else {@link #DEFAULT_RATE} ({@code lookupRate} 227–254; line 51);
 *       international (dialed 00/+) = the ROOT's 117-plan rate at every tier ({@code IntlOutRating.lookupIntlRate} 57–74), {@link #DEFAULT_RATE}
 *       when no 117 prefix matches ({@code ReserveBalanceStep} 85–87);</li>
 *   <li>the accounts that may pay ({@code ReserveBalanceStep} 78–80): international is cash-only ({@code bdtAccounts} 209–219), else packages
 *       before cash, unexpired only ({@code orderedAccounts} 159–185, {@code unexpired} 187–207) — the switch ledger's {@link AccountOrder};</li>
 *   <li>the talk time billed ({@code BalanceBillingService.calculateCharged} 225–240): a package minute per 60 s; cash = rate / 60 × seconds;
 *       international first takes the matched 117 rate's minimum time and pulse ({@code IntlOutRating.chargedDurationSec} 80–84).</li>
 * </ul>
 * The tables are the test's: a partner's domestic plan by {@code <tier>#<partner>} (prefix → rate per minute) and the root's 117 plan
 * (prefix → {@link Rate}: amount, surcharge time, resolution). A real lane reads the tree's rate plans the same way.
 */
public final class CallRates {

    /** {@code BalanceBillingService} 34, {@code ReserveBalanceStep} 51. */
    public static final BigDecimal DEFAULT_RATE = new BigDecimal("0.40");
    public static final BigDecimal ONE_MINUTE = new BigDecimal("1.0");

    private final Map<String, Map<String, BigDecimal>> domesticPlans;
    private final Map<String, Rate> intlPlan117;

    public CallRates(Map<String, Map<String, BigDecimal>> domesticPlans, Map<String, Rate> intlPlan117) {
        this.domesticPlans = Map.copyOf(domesticPlans);
        this.intlPlan117 = Map.copyOf(intlPlan117);
    }

    /** A 117-plan row: the rate per minute, the minimum time (surcharge) and the pulse (resolution), in seconds. */
    public static Rate intl(String amount, int surchargeSec, int resolutionSec) {
        Rate r = new Rate();
        r.setRateAmount(new BigDecimal(amount));
        r.setSurchargeTime(surchargeSec);
        r.setResolution(resolutionSec);
        return r;
    }

    // ── the accounts (P2, P3) ───────────────────────────────────────────────

    /** The accounts that may pay this call at this tier, best first: cash only abroad, else packages before cash — unexpired only. */
    public List<PackageAccount> fundable(RoutesphereContext call, Tier tier, Partner partner, LocalDateTime now) {
        return isIntlOut(call) ? AccountOrder.cashOnly(partner, tier.tenant().getContext(), now) : AccountOrder.fundable(partner, tier.tenant().getContext(), now);
    }

    public static boolean isIntlOut(RoutesphereContext call) { return IntlOutRating.isIntlOut(call.billingCalledNumber()); }

    // ── the rate of one minute (P1, P13, P15) ───────────────────────────────

    /** What one minute costs on {@code account} at {@code tier}, for the number the call bills on NOW ({@code ReserveBalanceStep} 85–93). */
    public BigDecimal ratePerMinute(RoutesphereContext call, Tier tier, Partner partner, PackageAccount account) {
        if (isIntlOut(call)) return intlRatePerMinute(call);
        if (AccountOrder.TF_MIN.equals(account.getUom())) return ONE_MINUTE;
        return domesticRate(tier.dbName(), partner.getIdPartner(), call.billingCalledNumber());
    }

    /** The root's 117 rate of the dialed number (significant digits, longest prefix); {@link #DEFAULT_RATE} when none matches. */
    public BigDecimal intlRatePerMinute(RoutesphereContext call) {
        Rate matched = intlRate(call);
        return matched == null || matched.getRateAmount() == null ? DEFAULT_RATE : matched.getRateAmount();
    }

    /** The matched 117 row, or null ({@code IntlOutRating.lookupIntlRate} 57–74 over the test's own 117 table). */
    public Rate intlRate(RoutesphereContext call) {
        String significant = IntlOutRating.stripIntlAccess(call.billingCalledNumber());
        if (significant == null || significant.isEmpty()) return null;
        String best = null;
        for (String prefix : intlPlan117.keySet()) {
            if (significant.startsWith(prefix) && (best == null || prefix.length() > best.length())) best = prefix;
        }
        return best == null ? null : intlPlan117.get(best);
    }

    /** The partner's plan at this tier by the longest prefix of the called number ({@code lookupRate} 227–254); else the default. */
    public BigDecimal domesticRate(String tierDb, int partnerId, String calledNumber) {
        Map<String, BigDecimal> plan = domesticPlans.get(tierDb + "#" + partnerId);
        if (plan == null || calledNumber == null) return DEFAULT_RATE;
        String best = null;
        for (String prefix : plan.keySet()) {
            if (calledNumber.startsWith(prefix) && (best == null || prefix.length() > best.length())) best = prefix;
        }
        return best == null ? DEFAULT_RATE : plan.get(best);
    }

    /** The prefix the CDR names as the matched customer prefix: the called number's first three digits (the sample's rule). */
    public static String prefixOf(RoutesphereContext call) {
        String n = call.billingCalledNumber();
        return n == null || n.length() < 3 ? n : n.substring(0, 3);
    }

    // ── the cost at the end (P13, P14) ──────────────────────────────────────

    /** The seconds the call is charged for: abroad, the 117 row's minimum time and pulse first; at home, the talk time itself. */
    public double chargedSeconds(RoutesphereContext call) {
        Rate matched = isIntlOut(call) ? intlRate(call) : null;
        if (matched == null) return call.durationSec;
        int st = matched.getSurchargeTime() != null ? matched.getSurchargeTime() : 0;
        int res = matched.getResolution() != null ? matched.getResolution() : 1;
        return IntlOutRating.chargedDurationSec(call.durationSec, st, res);
    }

    /** The minutes billed, the same at every tier (each tier at its own rate): the charged seconds / 60, six decimals. */
    public BigDecimal billedMinutes(RoutesphereContext call) {
        return BigDecimal.valueOf(chargedSeconds(call)).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
    }

    /**
     * What one tier finally costs ({@code calculateCharged} 225–240): a package account pays the charged seconds / 60 minutes; cash pays
     * rate / 60 (ten decimals) × the charged seconds, six decimals — {@code rate} being the rate re-looked-up at the end (P13).
     */
    public BigDecimal cost(RoutesphereContext call, String uom, BigDecimal rate) {
        double seconds = chargedSeconds(call);
        if (AccountOrder.TF_MIN.equals(uom)) return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
        BigDecimal perSec = rate.divide(BigDecimal.valueOf(60), 10, RoundingMode.HALF_UP);
        return perSec.multiply(BigDecimal.valueOf(seconds)).setScale(6, RoundingMode.HALF_UP);
    }

    /** The rate the settlement re-looks-up on the TERMINATING number ({@code finalizeBalance} 130–143): abroad the root's 117, at home the plan. */
    public BigDecimal rateAtTheEnd(RoutesphereContext call, String tierDb, int partnerId, String uom) {
        if (isIntlOut(call)) return intlRatePerMinute(call);
        if (AccountOrder.TF_MIN.equals(uom)) return ONE_MINUTE;
        return domesticRate(tierDb, partnerId, call.billingCalledNumber());
    }
}
