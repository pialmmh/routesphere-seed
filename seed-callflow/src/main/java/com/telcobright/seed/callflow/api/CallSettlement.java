package com.telcobright.seed.callflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The settlement of a call, for every application: every tier pays what the application's settle rule says and the rest
 * of its reserve goes back. It runs exactly once per call, whatever way the call ended.
 *
 * <p>This is the work behind {@link CallFlow#settle}; the rule itself is {@link CallFlowSteps#chargeAtSettle}.
 */
final class CallSettlement<C extends CallFlowContext> {

    private final CallFlow<C> flow;

    CallSettlement(CallFlow<C> flow) { this.flow = flow; }

    /** Once per call: the balance child's SETTLING and the supervisor's end both come here, and the second finds it done. */
    void settle(C ctx) {
        synchronized (ctx) {
            if (ctx.reservesClosed) return;
            ctx.reservesClosed = true;
        }
        fixBilledDuration(ctx);
        List<TierSettlement> settlements = new ArrayList<>(ctx.levels.size());
        for (LevelAdmission level : ctx.levels) settlements.add(settleLevel(ctx, level));
        ctx.settlements = List.copyOf(settlements);
    }

    /** The duration the tiers are charged for is fixed here, once: the CDR carries the same number. */
    private void fixBilledDuration(C ctx) {
        ctx.durationSec = Math.max(0, flow.safely(ctx, "billedDuration", () -> flow.billedDuration(ctx, flow.kit().clock().millis()), ctx.durationSec));
    }

    private TierSettlement settleLevel(C ctx, LevelAdmission level) {
        if (!holdsReserve(level)) return TierSettlement.nothing(level.getLevelIndex());
        BigDecimal charged = null;
        try {
            charged = flow.chargeAtSettle(ctx, level);
            if (charged == null || charged.signum() < 0) charged = BigDecimal.ZERO;
            return flow.kit().ledger().settle(level, charged);
        } catch (RuntimeException e) {
            return owed(ctx, level, charged, e);
        }
    }

    /** The ledger did not take the settlement: nothing is lost silently — one error line names everything that is owed. */
    private TierSettlement owed(C ctx, LevelAdmission level, BigDecimal charged, RuntimeException why) {
        flow.counters().owed.incrementAndGet();
        flow.log.error("[{}] {} | OWED: the settlement of tier {} ({}) partner {} was NOT taken — reference {}, reserved {} {}, to charge {}: {}",
            flow.name(), ctx.sessionKey, level.getLevelIndex(), level.getDbName(), level.getPartnerId(), level.getDebitReference(),
            level.getTotalReserved(), level.getUom(), charged, why.toString());
        return TierSettlement.owed(level, charged == null ? BigDecimal.ZERO : charged, why.toString());
    }

    /**
     * A tier the ledger was asked to hold — money, or a counted reservation of zero: the call switch's step reserves the rate even
     * when it is zero and its settlement must run once for every such tier, unconditionally, because the reserve row dies there
     * (a zero-rated tier skipped at settle leaves its row behind: BTCL, 2026-09-28). The base's own reserve never holds zero.
     */
    static boolean holdsReserve(LevelAdmission level) {
        if (level.getDebitReference() == null) return false;
        boolean money = level.getTotalReserved() != null && level.getTotalReserved().signum() > 0;
        return money || level.getReservationCount() > 0;
    }
}
