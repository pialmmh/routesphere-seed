package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.prepaid.api.Final;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.TierSettlement;

import java.math.BigDecimal;

/**
 * The final state, ONE tier of a lane with a Prepaid of its own (L2a 2b): the lane's {@code finalize} answers a {@link Final}, applied
 * through the kit's one ledger port as the moved settlement applies a charge — a hold settles in both directions (the rest back, an
 * overrun taken: {@code BalanceBillingService.settleAgainstReserve} 176–197) and its row dies. Asked once per tier; a throw (or no answer)
 * leaves the tier OWED (the moved settlement says it with the reference).
 */
final class FinalMoney<C extends RoutesphereContext> {

    private final LaneAnswers<C> flow;
    private final PrepaidRules<C> rules;

    FinalMoney(LaneAnswers<C> flow, PrepaidRules<C> rules) {
        this.flow = flow;
        this.rules = rules;
    }

    TierSettlement settle(C ctx, LevelAdmission tier) {
        Final end = rules.finalRule().apply(ctx);
        if (end == null) throw new IllegalStateException("[" + rules.name() + "] finalize answered no Final for session " + ctx.sessionId());
        return flow.kit().ledger().settle(tier, chargeOfAHold(tier, end));
    }

    /** What a held tier finally costs: the lane's cost; nothing for a release or a refund; exactly what it held when nothing more moves. */
    private static BigDecimal chargeOfAHold(LevelAdmission tier, Final end) {
        BigDecimal charged = switch (end) {
            case Final.Charge charge -> charge.cost().apply(tier);
            case Final.ReleaseAll releaseAll -> BigDecimal.ZERO;
            case Final.RefundAll refundAll -> BigDecimal.ZERO;
            case Final.Nothing nothing -> TierSettlement.reservedOf(tier);
        };
        return charged == null || charged.signum() < 0 ? BigDecimal.ZERO : charged;
    }
}
