package com.telcobright.seed.campaign.api;

import java.math.BigDecimal;

/**
 * What a finished task cost, in routesphere's {@code campaign_task} billing columns: the account
 * ({@code idPackageAccount}), the units taken from it ({@code packageAmount} in {@code uom}), the money
 * ({@code inPartnerCost}, BDT) and the rate row that decided it ({@code MatchedPrefixCustomer} = the matched pattern).
 */
public record TaskCharge(Long packageAccountId, String uom, BigDecimal packageAmount, BigDecimal cost, String matchedPattern) {

    public static final TaskCharge FREE = new TaskCharge(null, null, BigDecimal.ZERO, BigDecimal.ZERO, null);

    public boolean free() { return packageAccountId == null; }
}
