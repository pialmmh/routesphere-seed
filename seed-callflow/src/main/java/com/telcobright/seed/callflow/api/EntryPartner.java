package com.telcobright.seed.callflow.api;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.util.Objects;

/**
 * Who a call belongs to: the partner that pays at the leaf tier, and the tenant that partner lives in. The tenant chain
 * is walked from this tenant up to the root.
 */
public record EntryPartner(Tenant tenant, Partner partner) {

    public EntryPartner {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(partner, "partner");
    }
}
