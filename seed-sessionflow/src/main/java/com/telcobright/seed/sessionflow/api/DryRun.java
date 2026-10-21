package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.statewalk.session.TransitionRecord;

import java.util.List;

/**
 * What a session WOULD come to, without one side effect (a lane's routing admin, a price preview): the same start and the same admission
 * walk as a live session — the root, the task, the candidates, the tiers with their rates, the route — but nothing is reserved, no slot is
 * taken, no machine is used and no CDR is written ({@link RoutesphereFlow#simulate}).
 *
 * @param levels the tiers with their rates, the leaf first (empty when refused)
 * @param plan   the route the admitted candidate would take (null when refused)
 * @param trace  every step with its result and its time
 */
public record DryRun(boolean admitted, String cause, List<LevelAdmission> levels, RoutePlan plan, String incomingRoute, String outgoingRoute,
                     List<TransitionRecord> trace) {

    static DryRun of(boolean admitted, String cause, RoutesphereContext ctx) {
        return new DryRun(admitted, cause, ctx.levels, ctx.routePlan, ctx.incomingRoute, ctx.outgoingRoute, ctx.history.snapshot());
    }
}
