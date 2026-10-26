package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.SettleOutcome;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.statewalk.session.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * (e) The money at the end of a session, for every application, exactly once per session whatever way it ended — under the session's
 * money lock (the budget child renews on its own cell):
 * <ul>
 *   <li>{@link #settle} — an ANSWERED session (statewalk's SUCCEEDED, and its FAILED after the answer, D-A1): every tier is finalized by
 *       {@code finalizeTier} — delivered → rate × billed units, the rest back; not delivered → the refund rule;</li>
 *   <li>{@link #release} — a session never answered: every reserve back; only a session OFFERED and never consumed may pay, when the lane's
 *       refund rule charges it (the ad's {@code unshown}).</li>
 * </ul>
 * A tier the ledger did not take is OWED: one ERROR names the reference, the CDR says {@code settle: OWED}.
 *
 * <p>L2a 2a: MOVED as it was from the base ({@code seed-sessionflow api/SessionSettlement}, design §11), with the base's default
 * {@code finalizeTier} ({@link #settleTier}) and its refund-rule charge ({@link #chargeByTheRefundRule}); the lane's answers are read
 * through {@link LaneAnswers} (the flow's own hooks).
 */
final class SessionSettlement<C extends RoutesphereContext> {

    /** The base's own log name: the lines read exactly as before the move. */
    private static final Logger log = LoggerFactory.getLogger(RoutesphereFlow.class);

    private final LaneAnswers<C> flow;

    private SessionSettlement(LaneAnswers<C> flow) { this.flow = flow; }

    static <C extends RoutesphereContext> SessionSettlement<C> of(LaneAnswers<C> flow) { return new SessionSettlement<>(flow); }

    void settle(C ctx, Outcome outcome) {
        ctx.money.lock();
        try {
            if (!closeOnce(ctx)) return;
            fixBilledDuration(ctx);
            List<TierSettlement> settlements = new ArrayList<>(ctx.levels.size());
            for (LevelAdmission level : ctx.levels) settlements.add(finalizeTier(ctx, level, outcome));
            ctx.settlements = List.copyOf(settlements);
        } finally {
            ctx.money.unlock();
        }
    }

    void release(C ctx) {
        ctx.money.lock();
        try {
            if (!closeOnce(ctx)) return;
            fixBilledDuration(ctx);
            List<TierSettlement> settlements = new ArrayList<>(ctx.levels.size());
            for (LevelAdmission level : ctx.levels) settlements.add(giveBack(ctx, level));
            ctx.settlements = List.copyOf(settlements);
        } finally {
            ctx.money.unlock();
        }
    }

    /** Exactly once per session: the second ask (a re-entered final, a restored session ending again) finds it done. */
    private static boolean closeOnce(RoutesphereContext ctx) {
        if (ctx.reservesClosed) return false;
        ctx.reservesClosed = true;
        return true;
    }

    /** The duration the tiers are charged for is fixed here, once: the CDR carries the same number. */
    private void fixBilledDuration(C ctx) {
        ctx.durationSec = Math.max(0, flow.safely(ctx, "billedDuration", () -> flow.billedDuration(ctx, flow.kit().clock().millis()), ctx.durationSec));
    }

    private TierSettlement finalizeTier(C ctx, LevelAdmission level, Outcome outcome) {
        if (!holdsReserve(level)) return TierSettlement.nothing(level.getLevelIndex());
        try {
            TierSettlement done = flow.finalizeTier(ctx, level, outcome);
            return done != null ? done : TierSettlement.nothing(level.getLevelIndex());
        } catch (RuntimeException e) {
            return owed(ctx, level, null, e);
        }
    }

    /** Never answered: the reserve goes back — unless the refund rule charges a session offered and never consumed (its whole reserve). */
    private TierSettlement giveBack(C ctx, LevelAdmission level) {
        if (!holdsReserve(level)) return TierSettlement.nothing(level.getLevelIndex());
        BigDecimal charged = null;
        try {
            charged = chargeByTheRefundRule(ctx, level);
            if (charged.signum() > 0) return flow.kit().ledger().settle(level, charged);
            flow.kit().ledger().release(level, ctx.endCause == null ? SessionCause.NO_ANSWER : ctx.endCause);
            TierReserve.of(flow).journalRelease(ctx, level);
            return TierSettlement.of(level, BigDecimal.ZERO, level.getBalanceBefore());
        } catch (RuntimeException e) {
            return owed(ctx, level, charged, e);
        }
    }

    /**
     * (e) The tier's final cost, in the final state — the base's default {@code finalizeTier}: delivered → charged = rate × billed units
     * ({@code chargeAtSettle}), the rest back (negative = an extra debit, the call's rule); not delivered → the refund rule
     * ({@code refundPolicy} on {@code settleOutcome}). Through the one ledger port, idempotent per (session, tier).
     */
    TierSettlement settleTier(C ctx, LevelAdmission tier, Outcome outcome) {
        BigDecimal charged = flow.delivered(ctx, outcome) ? flow.chargeAtSettle(ctx, tier) : chargeByTheRefundRule(ctx, tier);
        if (charged == null || charged.signum() < 0) charged = BigDecimal.ZERO;
        return flow.kit().ledger().settle(tier, charged);
    }

    /** What a tier not delivered pays: its whole reserve when the lane's refund rule charges the outcome, else nothing. */
    BigDecimal chargeByTheRefundRule(C ctx, LevelAdmission tier) {
        RefundPolicy policy = flow.refundPolicy(ctx);
        SettleOutcome outcome = flow.settleOutcome(ctx);
        if (policy == null) policy = RefundPolicy.callsRule();
        if (outcome == null) outcome = ctx.answered() ? SettleOutcome.CONSUMED : SettleOutcome.FAILED;
        return policy.pays(outcome) ? TierSettlement.reservedOf(tier) : BigDecimal.ZERO;
    }

    private TierSettlement owed(C ctx, LevelAdmission level, BigDecimal charged, RuntimeException why) {
        flow.kit().counters().owed.incrementAndGet();
        log.error("[{}] {} | OWED: the settlement of tier {} ({}) partner {} was NOT taken — reference {}, reserved {} {}, to charge {}: {}",
            flow.name(), LogText.clean(ctx.sessionId()), level.getLevelIndex(), level.getDbName(), level.getPartnerId(), LogText.clean(level.getDebitReference()),
            level.getTotalReserved(), level.getUom(), charged, LogText.failureText(why));
        return TierSettlement.owed(level, charged == null ? BigDecimal.ZERO : charged, why.toString());
    }

    /**
     * A tier the ledger was asked to hold — money, or a counted reservation of zero (the call switch reserves a zero rate too, and its
     * settlement must run once for every such tier: the reserve row dies there).
     */
    static boolean holdsReserve(LevelAdmission level) {
        if (level.getDebitReference() == null) return false;
        boolean money = level.getTotalReserved() != null && level.getTotalReserved().signum() > 0;
        return money || level.getReservationCount() > 0;
    }
}
