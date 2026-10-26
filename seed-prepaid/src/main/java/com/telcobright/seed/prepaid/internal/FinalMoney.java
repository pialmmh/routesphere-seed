package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.prepaid.api.Final;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.TierSettlement;

import java.math.BigDecimal;

/**
 * The final state, ONE tier of a lane with a Prepaid of its own (L2a 2b): the lane's {@code finalize} answers a {@link Final}, applied
 * through the kit's one ledger port. A HELD tier settles as the moved settlement settles a charge — in both directions (the rest back, an
 * overrun taken: {@code BalanceBillingService.settleAgainstReserve} 176–197) and its row dies. A CHARGED tier (CHARGE_NOW, item 4): the
 * charge stands ({@code Final.nothing}), comes back whole ({@code refundAll}, {@code releaseAll}), or comes back down to the lane's cost
 * ({@code charge}; a cost above the charge is OWED, never taken). Asked once per tier; a throw (or no answer) leaves the tier OWED (the
 * moved settlement says it with the reference).
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
        if (rules.chargesNow()) return settleACharge(tier, end);
        return MoneyUnit.moves(flow.kit().ledger()).settle(tier, costOf(tier, end, TierSettlement.reservedOf(tier)));
    }

    /** A charged tier: the charge stands, or the part the Final does not keep is refunded through the port. */
    private TierSettlement settleACharge(LevelAdmission tier, Final end) {
        BigDecimal taken = TierSettlement.reservedOf(tier);
        BigDecimal cost = costOf(tier, end, taken);
        if (cost.compareTo(taken) > 0) {
            return TierSettlement.owed(tier, cost, "the final cost " + cost + " is more than the " + taken + " charged at admission: the shortfall is not taken");
        }
        BigDecimal back = taken.subtract(cost);
        if (back.signum() == 0) return TierSettlement.of(tier, taken, tier.getBalanceAfter());
        return MoneyUnit.moves(flow.kit().ledger()).refund(tier, back, end.getClass().getSimpleName());
    }

    /**
     * What the tier finally costs: the lane's cost; nothing for a release or a refund; exactly what was taken at admission when nothing more
     * moves (a hold: settled at what it held; a charge: it stands). Null or negative = nothing.
     */
    private static BigDecimal costOf(LevelAdmission tier, Final end, BigDecimal taken) {
        BigDecimal cost = switch (end) {
            case Final.Charge charge -> charge.cost().apply(tier);
            case Final.ReleaseAll releaseAll -> BigDecimal.ZERO;
            case Final.RefundAll refundAll -> BigDecimal.ZERO;
            case Final.Nothing nothing -> taken;
        };
        return cost == null || cost.signum() < 0 ? BigDecimal.ZERO : cost;
    }
}
