package com.telcobright.seed.callflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.statewalk.session.TransitionRecord;

import java.util.List;

/**
 * What a call WOULD come to, without one side effect: the same preprocessing and the same admission chain as a live
 * call, but nothing is reserved, no slot is taken, no machine is used and no CDR is written.
 *
 * @param levels the tiers with their rates, the leaf first (empty when refused)
 * @param trace  every step with its result and its time
 */
public record DryRun(boolean admitted, String cause, List<LevelAdmission> levels, String incomingRoute, String outgoingRoute,
                     List<TransitionRecord> trace) {

    static DryRun of(boolean admitted, String cause, CallFlowContext ctx) {
        return new DryRun(admitted, cause, ctx.levels, ctx.incomingRoute, ctx.outgoingRoute, ctx.history.snapshot());
    }
}
