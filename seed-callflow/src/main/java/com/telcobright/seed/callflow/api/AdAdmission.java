package com.telcobright.seed.callflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;

import java.util.List;

/**
 * What the chain admission of one candidate came to (design §3 item 2): admitted with one {@link LevelAdmission} per tier
 * (leaf first, every tier DEBITED), or refused with a cause ({@link AdCause} names) — the tiers debited before the refusal
 * are already CREDITED back by the port (the only automatic refund there is), so a refused admission carries no levels.
 * {@code entryTenant} / {@code entryPartner} are what was identified before the refusal, for the failed CDR row.
 
 *
 * @deprecated The ad-only shape of 2026-09-29. Since the base call pipeline (2026-10-03): the base answers with {@code AdmissionVerdict} and keeps the tiers on {@link CallFlowContext#levels} ({@link CallFlow#admit}).
 *     Removed when ad-sphere has moved onto {@code CallFlow}.
 */
@Deprecated(since = "2026-10-03", forRemoval = true)
public record AdAdmission(boolean admitted, String cause, List<LevelAdmission> levels, Tenant entryTenant, Partner entryPartner) {

    public AdAdmission {
        levels = levels == null ? List.of() : List.copyOf(levels);
    }

    public static AdAdmission admitted(List<LevelAdmission> levels, Tenant entryTenant, Partner entryPartner) {
        return new AdAdmission(true, null, levels, entryTenant, entryPartner);
    }

    public static AdAdmission refused(String cause, Tenant entryTenant, Partner entryPartner) {
        return new AdAdmission(false, cause, List.of(), entryTenant, entryPartner);
    }

    public static AdAdmission refused(AdCause cause, Tenant entryTenant, Partner entryPartner) {
        return refused(cause.name(), entryTenant, entryPartner);
    }

    /** Was the refusal the money system's fault (never a customer cause)? */
    public boolean systemFault() { return !admitted && AdCause.isSystemFault(cause); }
}
