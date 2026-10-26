package com.telcobright.seed.prepaid.spi;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.prepaid.api.PayingTier;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.statewalk.session.Verdict;

import java.math.BigDecimal;
import java.util.List;

/**
 * The optional callbacks of a lane's Prepaid — every one with a default that does nothing (design §10: {@code beforeHold},
 * {@code onExhausted}, {@code onLowBalance}, {@code onFinished}). A lane writes them as lambdas on the builder, or as one class here.
 * They are shared rules: no session state in them (what a session learns goes on its context). The base guards each: a throw in
 * {@code beforeHold} refuses the session ({@code INTERNAL_ERROR}, nothing kept); a throw anywhere else is logged and the session goes on.
 *
 * @param <C> the lane's context
 */
public interface PrepaidHooks<C extends RoutesphereContext> {

    /** ADMITTING, before each account of a tier is held: ok = hold it; a reject = the session is refused with its cause. */
    default Verdict beforeHold(C ctx, PayingTier tier, PackageAccount account) { return Verdict.ok(); }

    /**
     * ACTIVE, windowed lanes · a renewal found the money ending: the next window is only partly funded (the cut is armed for when it ends)
     * or not at all (the cut now). The base cuts the session either way (BudgetExhausted → Stop); here the lane says it its own way.
     */
    default void onExhausted(C ctx) { }

    /** After a tier was held or charged, its account's balance is under the lane's threshold. */
    default void onLowBalance(C ctx, LevelAdmission tier, BigDecimal balanceAfter) { }

    /** The final state, after the money of every tier was settled: one settlement per tier, the leaf first (the app's own record). */
    default void onFinished(C ctx, List<TierSettlement> money) { }
}
