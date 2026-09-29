package com.telcobright.seed.callflow.internal;

import com.telcobright.rtc.domainmodel.mysqlentity.RatePlan;
import com.telcobright.rtc.domainmodel.mysqlentity.ad.AdRate;
import com.telcobright.rtc.domainmodel.nonentity.DynamicContext;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Rating ONE tier of the chain on THAT tier's plan (design §2.3): the tier partner's plans from {@code partnerWiseRatePlans}
 * (first by priority, as {@code ReserveBalanceStep.lookupRate} takes the first plan), the plan's {@code ad_rate} rows from
 * {@code ratePlanWiseAdRates}; the LONGEST prefix on the called number (the rule's code) wins, at equal length a media row
 * beats {@code any}, then the lower id — ad-sphere's {@code AdRatePlan.better()} rule, unchanged. Per view = the amount
 * once; per second = amount × the charged seconds (the surcharge minimum, else the watch rounded up to the pulse) +
 * the surcharge amount. Rounded to the plan's {@code RateAmountRoundupDecimal} (null = 4).
 */
public final class TierRating {

    private TierRating() {}

    /** What a tier's rate came to. */
    public record Rated(RatePlan plan, AdRate rate, String prefix, BigDecimal amount, String currency) {}

    public static Optional<Rated> rate(Tenant tenant, int partnerId, String called, String mediaKind, int requiredSeconds, LocalDateTime at) {
        DynamicContext ctx = tenant.getContext();
        if (ctx == null) return Optional.empty();
        List<RatePlan> plans = ctx.getPartnerWiseRatePlans() == null ? null : ctx.getPartnerWiseRatePlans().get(String.valueOf(partnerId));
        if (plans == null || plans.isEmpty()) return Optional.empty();
        for (RatePlan plan : plans) {
            if (plan == null || plan.getId() == null) continue;
            if (!planLiveAt(plan, at)) continue;
            List<AdRate> rows = ctx.getRatePlanWiseAdRates() == null ? null : ctx.getRatePlanWiseAdRates().get(plan.getId());
            if (rows == null || rows.isEmpty()) continue;
            AdRate best = null;
            for (AdRate r : rows) {
                if (r == null || !r.liveAt(at) || !r.matches(called, mediaKind)) continue;
                if (best == null || better(r, best)) best = r;
            }
            if (best != null) {
                return Optional.of(new Rated(plan, best, best.prefixText(), amountOf(best, plan, requiredSeconds), plan.getCurrency()));
            }
        }
        return Optional.empty();
    }

    /** The assignment's window rides on the plan ({@code DataLoader.getPartnerWiseRatePlans} copies rateassign's dates onto it). */
    private static boolean planLiveAt(RatePlan plan, LocalDateTime at) {
        if (plan.getStartDate() != null && at.isBefore(plan.getStartDate())) return false;
        return plan.getEndDate() == null || at.isBefore(plan.getEndDate());
    }

    static boolean better(AdRate r, AdRate best) {
        int rl = r.prefixText().length(), bl = best.prefixText().length();
        if (rl != bl) return rl > bl;
        boolean rSpecific = !AdRate.MEDIA_ANY.equals(r.mediaWord()), bSpecific = !AdRate.MEDIA_ANY.equals(best.mediaWord());
        if (rSpecific != bSpecific) return rSpecific;
        long ri = r.getId() == null ? Long.MAX_VALUE : r.getId(), bi = best.getId() == null ? Long.MAX_VALUE : best.getId();
        return ri < bi;
    }

    /** The one debit's amount for the view's FULL length (no reserve, no refund). */
    public static BigDecimal amountOf(AdRate rate, RatePlan plan, int requiredSeconds) {
        BigDecimal raw;
        if (!rate.perSecond()) {
            raw = rate.getRateAmount() == null ? BigDecimal.ZERO : rate.getRateAmount();
        } else {
            int charged = chargedSeconds(Math.max(requiredSeconds, 1), rate.getResolution() == null ? 1 : rate.getResolution(),
                rate.getSurchargeTime() == null ? 0 : rate.getSurchargeTime());
            raw = (rate.getRateAmount() == null ? BigDecimal.ZERO : rate.getRateAmount()).multiply(BigDecimal.valueOf(charged));
            if (rate.getSurchargeAmount() != null) raw = raw.add(rate.getSurchargeAmount());
        }
        Integer scale = plan == null ? null : plan.getRateAmountRoundupDecimal();
        int s = scale == null ? 4 : Math.max(0, Math.min(8, scale));
        return raw.setScale(s, RoundingMode.HALF_UP);
    }

    /** Below the surcharge minimum → the minimum; else round up to the pulse (IntlOutRating's rule on seconds). */
    static int chargedSeconds(int watched, int resolution, int surchargeSec) {
        if (watched <= 0) return 0;
        if (surchargeSec > 0 && watched < surchargeSec) return surchargeSec;
        int pulse = Math.max(resolution, 1);
        return (int) (Math.ceil(watched / (double) pulse) * pulse);
    }
}
