package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.math.BigDecimal;

/**
 * ONE tier's money as the session's persisted context keeps it (§B, §L.6): ids and amounts only — the tier's database name, the
 * partner's id, the account, the rate, what was reserved under which reference and how often — never the tenant or the partner object.
 * The live {@link LevelAdmission} is the session's working copy (the ledger port's and the CDR's); this is its picture in the store, so a
 * session restored from its row settles exactly what it reserved. {@link #toLevel} rebuilds the live tier from the root's snapshot.
 *
 * <p>A public-field POJO with a no-arg constructor: Jackson-friendly as statewalk's store needs it.
 */
public final class TierLine {

    public int levelIndex;
    public String dbName;
    public int partnerId;
    public String partnerName;
    public Long packageAccountId;
    public Long chargeAccountId;
    public String uom;
    public BigDecimal rate;
    public String ratePrefix;
    public String usageKind;
    public int usageSeconds;
    public BigDecimal reservedAmount;
    public BigDecimal totalReserved;
    public int reservationCount;
    public String debitReference;
    public BigDecimal balanceBefore;
    public BigDecimal balanceAfter;

    public TierLine() {}

    /** The picture of a live tier. */
    public static TierLine of(LevelAdmission level) {
        TierLine line = new TierLine();
        line.levelIndex = level.getLevelIndex();
        line.dbName = level.getDbName();
        line.partnerId = level.getPartnerId();
        line.partnerName = level.getPartnerName();
        line.packageAccountId = level.getPackageAccountId();
        line.chargeAccountId = level.getChargeAccountId();
        line.uom = level.getUom();
        line.rate = level.getRate();
        line.ratePrefix = level.getRatePrefix();
        line.usageKind = level.getUsageKind();
        line.usageSeconds = level.getUsageSeconds();
        line.reservedAmount = level.getReservedAmount();
        line.totalReserved = level.getTotalReserved();
        line.reservationCount = level.getReservationCount();
        line.debitReference = level.getDebitReference();
        line.balanceBefore = level.getBalanceBefore();
        line.balanceAfter = level.getBalanceAfter();
        return line;
    }

    /**
     * The live tier again, on the root's snapshot: the tenant found in THE SESSION'S OWN tree by its database name (else a shell of that
     * name), the partner by its id in that tier (else a shell with the id), the account as an id. The amounts are the picture's.
     */
    public LevelAdmission toLevel(RootTree tree) {
        Tenant tenant = tree == null ? null : tree.tier(dbName).orElse(null);
        if (tenant == null) tenant = new Tenant(dbName);
        Partner partner = tree == null ? null : tree.partnerOf(dbName, partnerId);
        if (partner == null) { partner = new Partner(); partner.setIdPartner(partnerId); }
        LevelAdmission level = new LevelAdmission(levelIndex, tenant, partner, accountOf(packageAccountId, uom));
        if (rate != null) level.setRate(rate);
        if (uom != null) level.setUom(uom);
        level.setRatePrefix(ratePrefix);
        level.setUsageKind(usageKind);
        level.setUsageSeconds(usageSeconds);
        level.setReservedAmount(reservedAmount == null ? BigDecimal.ZERO : reservedAmount);
        BigDecimal more = (totalReserved == null ? BigDecimal.ZERO : totalReserved).subtract(level.getTotalReserved() == null ? BigDecimal.ZERO : level.getTotalReserved());
        if (more.signum() != 0) level.addToTotalReserved(more);
        for (int i = 0; i < reservationCount; i++) level.incrementReservationCount();
        level.setDebitReference(debitReference);
        level.setChargeAccountId(chargeAccountId);
        level.setBalanceBefore(balanceBefore);
        level.setBalanceAfter(balanceAfter);
        return level;
    }

    private static PackageAccount accountOf(Long id, String uom) {
        if (id == null) return null;
        PackageAccount account = new PackageAccount();
        account.setId(id);
        if (uom != null) account.setUom(uom);
        return account;
    }

    @Override
    public String toString() {
        return "tier " + levelIndex + " (" + dbName + ") partner " + partnerId + " reserved " + totalReserved + " " + uom + " under " + debitReference;
    }
}
