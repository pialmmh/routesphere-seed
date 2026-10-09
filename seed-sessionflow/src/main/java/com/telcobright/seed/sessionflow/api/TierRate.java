package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;

import java.math.BigDecimal;

/**
 * What ONE tier's rating came to: the amount to reserve now, and the facts the CDR keeps about the rate.
 *
 * @param reserveAmount what admission reserves at this tier (a call: one unit of talk time; an SMS: every part; an ad
 *                      view: the whole view). Zero = the tier is walked and recorded, nothing is reserved
 * @param rate          the rate itself (per minute, per message, per view) — the CDR's customer rate
 * @param uom           the unit the tier pays in: {@code BDT} (money) or a package unit ({@code TF_min}, {@code OTH_ea})
 * @param ratePrefix    the prefix of the rate row that matched — the CDR's matched customer prefix
 * @param account       the switch's own package account, when the ledger is the switch's (the call). Null when the
 *                      ledger picks the account itself (orchestrix)
 * @param usageKind     what is used, in the ledger's word (the ad: the media kind). May be null
 * @param usageSeconds  how much of it, in seconds, when the rate is per second. 0 otherwise
 * @param held          the tier as the application's OWN step admitted it — rated, the account chosen, one unit held in one
 *                      move (the call switch's {@code ReserveBalanceStep}: the reserve IS its affordability test). The base
 *                      takes this level as the tier's, names its reference and asks its ledger nothing for it; in a dry run
 *                      the step's own mock (nothing held) is taken the same way. Null = the base rates from the fields above
 *                      and reserves through the kit's ledger
 */
public record TierRate(BigDecimal reserveAmount, BigDecimal rate, String uom, String ratePrefix, PackageAccount account,
                       String usageKind, int usageSeconds, LevelAdmission held) {

    public TierRate {
        if (reserveAmount == null || reserveAmount.signum() < 0) throw new IllegalArgumentException("reserveAmount must be zero or more");
        if (rate == null) rate = BigDecimal.ZERO;
    }

    /** The rate of a tier the base reserves itself (the shape before {@link #held}). */
    public TierRate(BigDecimal reserveAmount, BigDecimal rate, String uom, String ratePrefix, PackageAccount account, String usageKind, int usageSeconds) {
        this(reserveAmount, rate, uom, ratePrefix, account, usageKind, usageSeconds, null);
    }

    /** A tier that pays nothing: walked, recorded, never reserved. */
    public static TierRate free() { return new TierRate(BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, 0); }

    public static TierRate of(BigDecimal reserveAmount, BigDecimal rate, String uom, String ratePrefix) {
        return new TierRate(reserveAmount, rate, uom, ratePrefix, null, null, 0);
    }

    /**
     * A tier the application's own step admitted: it rated it, chose the account and holds its reserve already (or, in a dry run,
     * rated it and holds nothing). The base takes the level as it is.
     */
    public static TierRate held(LevelAdmission level) {
        if (level == null) throw new IllegalArgumentException("a held tier needs its level");
        return new TierRate(TierSettlement.reservedOf(level), level.getRate(), level.getUom(), level.getRatePrefix(), level.getAccount(),
            level.getUsageKind(), level.getUsageSeconds(), level);
    }

    public TierRate onAccount(PackageAccount packageAccount) {
        return new TierRate(reserveAmount, rate, uom, ratePrefix, packageAccount, usageKind, usageSeconds, held);
    }

    public TierRate usage(String kind, int seconds) {
        return new TierRate(reserveAmount, rate, uom, ratePrefix, account, kind, seconds, held);
    }

    public boolean reserves() { return reserveAmount.signum() > 0; }
}
