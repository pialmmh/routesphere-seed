package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.switchledger.api.WindowRenewal;

import java.math.BigDecimal;

/**
 * ACTIVE, ONE tier of a HOLD_WINDOW lane (L2a 2b; design §10.2, P9): the next window is one unit at the tier's own rate, held through the
 * moved hold under {@code …#W<n>} — the whole unit, else the REMAINDER the account still has, in seconds of the window (routesphere-core
 * {@code BalanceBillingService.reserveNextWindowSeconds} 86–120, through the switch ledger's {@link WindowRenewal}: below 1 s nothing); a
 * zero rate keeps the cadence and holds nothing. The peek is the kit's ledger port's ({@code balanceOf}).
 */
final class WindowedRenewal<C extends RoutesphereContext> {

    private final LaneAnswers<C> flow;

    WindowedRenewal(LaneAnswers<C> flow) { this.flow = flow; }

    double renew(C ctx, LevelAdmission tier) {
        double period = flow.kit().settings().reservePeriodSec();
        if (tier.getDebitReference() == null) return period;                     // nothing held at admission (a zero rate): nothing to renew
        BigDecimal unit = tier.getRate() == null ? BigDecimal.ZERO : tier.getRate();
        String reference = tier.getDebitReference() + "#W" + (tier.getReservationCount() + 1);
        return renewal(ctx).renewSeconds(tier, unit, reference, unit, period);
    }

    private WindowRenewal renewal(C ctx) {
        TierReserve<C> holds = TierReserve.of(flow);
        return new WindowRenewal(MoneyUnit.moves(flow.kit().ledger())::balanceOf, (level, amount, reference) -> holds.reserveForRenewal(ctx, level, amount, reference));
    }
}
