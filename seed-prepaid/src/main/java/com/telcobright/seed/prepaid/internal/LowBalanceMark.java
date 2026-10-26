package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;

import java.math.BigDecimal;

/** After a hold or a charge: the lane is told when its account's balance fell under its mark ({@code onLowBalance}); a throw is logged. */
final class LowBalanceMark {

    private LowBalanceMark() {}

    static <C extends RoutesphereContext> void check(LaneAnswers<C> flow, PrepaidRules<C> rules, C ctx, LevelAdmission tier) {
        BigDecimal mark = rules.lowBalanceAt();
        BigDecimal after = tier.getBalanceAfter();
        if (mark == null || after == null || after.compareTo(mark) >= 0) return;
        flow.safely(ctx, "onLowBalance", () -> { rules.hooks().onLowBalance(ctx, tier, after); return null; }, null);
    }
}
