package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
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

    /**
     * L2b item 2 · Every tier the candidate's walk planned, held in ONE unit of the ledger under every account's lock (taken once, in the
     * global order): all tiers or none — a tier that cannot pay refuses the candidate with nothing held anywhere. Null = held.
     */
    @Override
    public String holdTheWalk(C ctx) {
        WalkPlan plan = WalkPlan.takeFrom(ctx);
        if (plan == null) return null;
        TierReserve<C> tiers = TierReserve.of(flow);
        LevelAdmission first = plan.accounts().isEmpty() ? null : plan.accounts().get(0);
        try (MoneyUnit unit = MoneyUnit.open(flow.kit().ledger(), plan.accounts())) {
            String refusal = holdEveryTier(plan);                       // each tier's hold asks the ledger within what the budget has left
            if (refusal == null) unit.commit("hold the walk of " + ctx.sessionId());
            else unit.abandon(refusal);
            return refusal;
        } catch (LedgerPort.LedgerRefusal refused) {
            return refused.code();
        } catch (LedgerPort.LedgerFault fault) {
            return first == null ? SessionCause.BILLING_SYSTEM_ERROR : tiers.ledgerFault(ctx, first.getLevelIndex(), first.getDbName(), first.getPartnerId(), fault);
        }
    }

    private static String holdEveryTier(WalkPlan plan) {
        for (WalkPlan.PlannedTier tier : plan.tiers()) {
            String refusal = tier.hold().get();
            if (refusal != null) return refusal;
        }
        return null;
    }

    /**
     * The call switch's compensateReserves (the base's {@code AdmissionChain.undo}): every tier the refused candidate held — after the walk's
     * one hold, a refusal of the route or the last gate — back in ONE unit; a CHARGE_NOW lane's charges given back whole (item 4).
     */
    @Override
    public void release(C ctx, List<LevelAdmission> held, String why) {
        if (held.isEmpty()) return;
        TierReserve<C> tiers = TierReserve.of(flow);
        try (MoneyUnit unit = MoneyUnit.open(flow.kit().ledger(), held)) {
            if (rules != null && rules.chargesNow()) { for (LevelAdmission level : held) tiers.refundCharge(ctx, level, why); }
            else for (LevelAdmission level : held) tiers.release(ctx, level, why);
            unit.commit("give back the walk of " + ctx.sessionId());
        } catch (RuntimeException e) {
            new UnitFault<>(flow).owedGiveBack(ctx, held, why, e);
        }
    }

    /** L2b item 2 · Every tier renews in ONE unit (the narrowest tier decides, as the live call); then the lane hears of an end. */
    @Override
    public double renewWindow(C ctx) {
        double period = flow.kit().settings().reservePeriodSec();
        double narrowest = narrowestInOneUnit(ctx, period);
        if (rules != null && narrowest < period) flow.safely(ctx, "onExhausted", () -> { rules.hooks().onExhausted(ctx); return null; }, null);
        return narrowest;
    }

    /** A unit the ledger refuses moved nothing: a fault never cuts a session (the settlement reconciles) — the period. */
    private double narrowestInOneUnit(C ctx, double period) {
        try (MoneyUnit unit = MoneyUnit.open(flow.kit().ledger(), ctx.levels)) {
            double narrowest = narrowestWindow(ctx, period);
            unit.commit("renew the windows of " + ctx.sessionId());
            return narrowest;
        } catch (LedgerPort.LedgerFault fault) {
            new UnitFault<>(flow).renewalNotWritten(ctx, fault);
            return period;
        }
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
        boolean open = !ctx.reservesClosed;
        settleInOneUnit(ctx, () -> {
            if (rules != null || answered) SessionSettlement.of(flow).settle(ctx, outcome);
            else SessionSettlement.of(flow).release(ctx);
        });
        if (rules != null && open) flow.safely(ctx, "onFinished", () -> { rules.hooks().onFinished(ctx, ctx.settlements); return null; }, null);
    }

    /** L2b item 2 · Every tier settled in ONE unit; a unit the ledger refuses leaves every tier OWED (held, said with its reference). */
    private void settleInOneUnit(C ctx, Runnable settleEveryTier) {
        ctx.money.lock();                                   // the session's money lock BEFORE the accounts' (the renewal's order)
        try (MoneyUnit unit = MoneyUnit.open(flow.kit().ledger(), ctx.levels)) {
            settleEveryTier.run();
            unit.commit("settle " + ctx.sessionId());
        } catch (LedgerPort.LedgerFault fault) {
            new UnitFault<>(flow).settlementNotWritten(ctx, fault);
        } finally {
            ctx.money.unlock();
        }
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
