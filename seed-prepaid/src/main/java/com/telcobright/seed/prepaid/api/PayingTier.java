package com.telcobright.seed.prepaid.api;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.Tier;

/**
 * ONE tier of the session's chain as the Prepaid's rules see it at admission: the tier (its tenant, its place in the chain) and the
 * partner that pays at it (the leaf's entry partner; above it, the partner that stands for the tier below).
 */
public record PayingTier(Tier tier, Partner partner) {

    public String dbName() { return tier.dbName(); }

    public int levelIndex() { return tier.levelIndex(); }

    public Tenant tenant() { return tier.tenant(); }

    public boolean isRoot() { return tier.isRoot(); }

    public int partnerId() { return partner.getIdPartner(); }
}
