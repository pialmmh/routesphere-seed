package com.telcobright.seed.callflow.spi;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.callflow.api.AdCallPayload;

import java.util.List;

/**
 * The terminal write (contract item 4, design §2.5) in ONE transaction: one CDR row per tier (none admitted → one row on the
 * entry tenant), the campaign task, the summary outbox row; commit; then the ping. Called from BOTH terminals — a reject and
 * a timeout get their row too, with the cause.
 */
public interface AdCdrPort {

    void writeAllLevels(AdCallPayload payload, List<LevelAdmission> levels, String cause, boolean answered);

    /** Nowhere (tests that do not look at the rows). */
    AdCdrPort NONE = (p, l, c, a) -> { };
}
