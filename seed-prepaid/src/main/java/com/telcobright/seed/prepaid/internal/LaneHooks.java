package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.prepaid.api.PayingTier;
import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.prepaid.spi.PrepaidHooks;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.statewalk.session.Verdict;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** A lane's callbacks as ONE {@link PrepaidHooks}: the builder's lambda where one was given, else the lane's hooks class, else nothing. */
public final class LaneHooks<C extends RoutesphereContext> implements PrepaidHooks<C> {

    private final PrepaidHooks<C> base;
    private final Prepaid.HoldGate<C> beforeHold;
    private final Consumer<C> onExhausted;
    private final Prepaid.LowBalance<C> onLowBalance;
    private final BiConsumer<C, List<TierSettlement>> onFinished;

    private LaneHooks(PrepaidHooks<C> base, Prepaid.HoldGate<C> beforeHold, Consumer<C> onExhausted, Prepaid.LowBalance<C> onLowBalance,
                      BiConsumer<C, List<TierSettlement>> onFinished) {
        this.base = base == null ? new PrepaidHooks<>() { } : base;
        this.beforeHold = beforeHold;
        this.onExhausted = onExhausted;
        this.onLowBalance = onLowBalance;
        this.onFinished = onFinished;
    }

    public static <C extends RoutesphereContext> PrepaidHooks<C> of(PrepaidHooks<C> base, Prepaid.HoldGate<C> beforeHold, Consumer<C> onExhausted,
                                                                   Prepaid.LowBalance<C> onLowBalance, BiConsumer<C, List<TierSettlement>> onFinished) {
        return new LaneHooks<>(base, beforeHold, onExhausted, onLowBalance, onFinished);
    }

    @Override
    public Verdict beforeHold(C ctx, PayingTier tier, PackageAccount account) {
        return beforeHold != null ? beforeHold.check(ctx, tier, account) : base.beforeHold(ctx, tier, account);
    }

    @Override
    public void onExhausted(C ctx) {
        if (onExhausted != null) onExhausted.accept(ctx); else base.onExhausted(ctx);
    }

    @Override
    public void onLowBalance(C ctx, LevelAdmission tier, BigDecimal balanceAfter) {
        if (onLowBalance != null) onLowBalance.warn(ctx, tier, balanceAfter); else base.onLowBalance(ctx, tier, balanceAfter);
    }

    @Override
    public void onFinished(C ctx, List<TierSettlement> money) {
        if (onFinished != null) onFinished.accept(ctx, money); else base.onFinished(ctx, money);
    }
}
