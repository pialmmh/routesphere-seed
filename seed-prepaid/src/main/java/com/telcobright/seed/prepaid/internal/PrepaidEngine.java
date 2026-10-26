package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.MoneyEngine;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Verdict;

import java.math.BigDecimal;
import java.util.List;

/**
 * The ONE money engine of the seed (L2a, design {@code prepaid-engine-design.md} §11): the base's money steps MOVED here as they were —
 * the tier's hold at admission and its give-back ({@link TierReserve}, from the base's {@code AdmissionChain}), the window renewal under the
 * session's money lock (the base's {@code renewReserves} loop) with the account rotation ({@link SpanRotation}), the settlement in both
 * directions and the release ({@link SessionSettlement}, with the base's default {@code finalizeTier}), the money of the sessions a stopped
 * process left in the air ({@link LeftoverMoney}, from the base's {@code SessionCdr}) — and, for a lane with a Prepaid of its own (2b),
 * the Prepaid's rules over those same steps: the account walk ({@link AccountWalk}), the window with its remainder
 * ({@link WindowedRenewal}), the end by the lane's {@code Final} ({@link FinalMoney}), the hooks. Each verb is ONE call the base makes; the
 * lane's answers are read through {@link LaneAnswers}. The order of operations is today's; every write goes through the kit's ONE ledger
 * port. Stateless beyond the lane's answers and rules: one engine serves every session of its lane.
 *
 * @param <C> the lane's context
 */
public final class PrepaidEngine<C extends RoutesphereContext> implements MoneyEngine<C> {

    private final LaneAnswers<C> flow;
    /** The lane's Prepaid rules; null = the lane's answers alone (the moved steps, exactly as they were). */
    private final PrepaidRules<C> rules;

    public PrepaidEngine(LaneAnswers<C> flow) { this(flow, null); }

    public PrepaidEngine(LaneAnswers<C> flow, PrepaidRules<C> rules) {
        this.flow = flow;
        this.rules = rules;
    }

    /** The lane's own rules, or null (the lane's answers). */
    public PrepaidRules<C> rules() { return rules; }

    @Override
    public Verdict admit(C ctx, Tier tier, Partner partner) {
        if (rules == null || flow.isFree(ctx, ctx.candidateBeingAdmitted())) return TierReserve.of(flow).rateAndReserve(ctx, tier, partner);
        return new AccountWalk<>(flow, rules).admit(ctx, tier, partner);
    }

    /** The call switch's compensateReserves (the base's {@code AdmissionChain.undo}): every tier the refused candidate held, back, in order. */
    @Override
    public void release(C ctx, List<LevelAdmission> held, String why) {
        TierReserve<C> tiers = TierReserve.of(flow);
        for (LevelAdmission level : held) tiers.release(ctx, level, why);
    }

    @Override
    public double renewWindow(C ctx) {
        double period = flow.kit().settings().reservePeriodSec();
        double narrowest = narrowestWindow(ctx, period);
        if (rules != null && narrowest < period) flow.safely(ctx, "onExhausted", () -> { rules.hooks().onExhausted(ctx); return null; }, null);
        return narrowest;
    }

    /** Every tier renews; the narrowest answer decides (the base's {@code RoutesphereFlow.narrowestWindow}, moved as it was). */
    private double narrowestWindow(C ctx, double period) {
        double narrowest = period;
        for (LevelAdmission level : ctx.levels) {
            narrowest = Math.min(narrowest, flow.safely(ctx, "renewWindowSeconds", () -> flow.renewWindowSeconds(ctx, level), period));
            if (narrowest <= 0) break;
        }
        return narrowest;
    }

    @Override
    public double renewTier(C ctx, LevelAdmission tier) {
        if (rules != null && rules.windowed()) return new WindowedRenewal<>(flow, rules).renew(ctx, tier);
        return TierReserve.of(flow).renewThroughLedger(ctx, tier);
    }

    @Override
    public String hold(C ctx, LevelAdmission tier, BigDecimal amount, String reference) {
        return TierReserve.of(flow).reserveForRenewal(ctx, tier, amount, reference);
    }

    /**
     * The final state. The lane's answers: answered → settle, else release (moved). A Prepaid of its own: its {@code finalize} decides every
     * tier, answered or not (through the lane's {@code finalizeTier}, whose default is {@link #settleTier}); then {@code onFinished}, once.
     */
    @Override
    public void finish(C ctx, Outcome outcome, boolean answered) {
        if (rules == null) { finishOnTheAnswers(ctx, outcome, answered); return; }
        boolean open = !ctx.reservesClosed;
        SessionSettlement.of(flow).settle(ctx, outcome);
        if (open) flow.safely(ctx, "onFinished", () -> { rules.hooks().onFinished(ctx, ctx.settlements); return null; }, null);
    }

    private void finishOnTheAnswers(C ctx, Outcome outcome, boolean answered) {
        if (answered) SessionSettlement.of(flow).settle(ctx, outcome);
        else SessionSettlement.of(flow).release(ctx);
    }

    @Override
    public TierSettlement settleTier(C ctx, LevelAdmission tier, Outcome outcome) {
        if (rules == null) return SessionSettlement.of(flow).settleTier(ctx, tier, outcome);
        return new FinalMoney<>(flow, rules).settle(ctx, tier);
    }

    @Override
    public int[] closeAsReserved(SessionJournal.Leftover left) { return new LeftoverMoney(flow).closeAsReserved(left); }

    @Override
    public boolean giveBackLeftover(String sessionId, SessionJournal.Held held) { return new LeftoverMoney(flow).giveBack(sessionId, held); }
}
