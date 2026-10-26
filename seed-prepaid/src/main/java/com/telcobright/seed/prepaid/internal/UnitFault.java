package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * L2b item 2 · A unit the ledger did not write (a lock not free in time, the ledger down, a refused record): NOTHING moved in any tier —
 * said with every reference, the way the moved steps say a tier the ledger did not take (OWED: counted, one ERROR per tier).
 */
final class UnitFault<C extends RoutesphereContext> {

    /** The base's own log name: the lines read as the moved steps' do. */
    private static final Logger log = LoggerFactory.getLogger(RoutesphereFlow.class);

    private final LaneAnswers<C> flow;

    UnitFault(LaneAnswers<C> flow) { this.flow = flow; }

    /** A refused candidate's give-back was not written: every tier it held is still held (the reaper's net), OWED. */
    void owedGiveBack(C ctx, List<LevelAdmission> held, String why, RuntimeException e) {
        for (LevelAdmission level : held) {
            flow.kit().counters().owed.incrementAndGet();
            log.error("[{}] {} | OWED: the reserve {} of {} {} at tier {} ({}) partner {} was NOT released ({}): {}", flow.name(),
                LogText.clean(ctx.sessionId()), LogText.clean(level.getDebitReference()), level.getTotalReserved(), level.getUom(), level.getLevelIndex(),
                level.getDbName(), level.getPartnerId(), LogText.clean(why), LogText.failureText(e));
        }
    }

    /** The renewal of every tier was not written: no window was held anywhere; the session goes on (a fault never cuts). */
    void renewalNotWritten(C ctx, RuntimeException fault) {
        log.error("[{}] {} | LEDGER FAULT at the renewal: no tier's window was written ({}) — the session goes on, the settlement reconciles",
            flow.name(), LogText.clean(ctx.sessionId()), LogText.failureText(fault));
        ctx.history.note(flow.name(), "the renewal of every tier was not written: " + fault.getMessage());
    }

    /** The settlement of every tier was not written: every tier that held stays held and is OWED, said with its reference. */
    void settlementNotWritten(C ctx, RuntimeException fault) {
        List<TierSettlement> owed = new ArrayList<>(ctx.levels.size());
        for (int i = 0; i < ctx.levels.size(); i++) {
            LevelAdmission level = ctx.levels.get(i);
            TierSettlement asked = i < ctx.settlements.size() ? ctx.settlements.get(i) : null;
            if (!SessionSettlement.holdsReserve(level)) { owed.add(asked != null ? asked : TierSettlement.nothing(level.getLevelIndex())); continue; }
            flow.kit().counters().owed.incrementAndGet();
            log.error("[{}] {} | OWED: the settlement of tier {} ({}) partner {} was NOT taken — reference {}, reserved {} {}: the unit of every tier was not written: {}",
                flow.name(), LogText.clean(ctx.sessionId()), level.getLevelIndex(), level.getDbName(), level.getPartnerId(), LogText.clean(level.getDebitReference()),
                level.getTotalReserved(), level.getUom(), LogText.failureText(fault));
            owed.add(TierSettlement.owed(level, asked == null ? java.math.BigDecimal.ZERO : asked.charged(), fault.toString()));
        }
        ctx.settlements = List.copyOf(owed);
    }
}
