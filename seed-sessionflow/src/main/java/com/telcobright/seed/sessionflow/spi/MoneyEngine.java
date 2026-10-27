package com.telcobright.seed.sessionflow.spi;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Verdict;

import java.math.BigDecimal;
import java.util.List;

/**
 * L2a · The money of a session, in the verbs the base CALLS and never does itself (design {@code prepaid-engine-design.md} §11, the
 * architect's module cut of 2026-10-26): the base keeps the lifecycle, the tiers, the ledger port and the lane's answers; EVERY money step —
 * the hold at admission ({@link #planReserve}), the give-back of a refused candidate ({@link #release}), the renewal of a window
 * ({@link #reserveNextWindow}), the settlement in both directions and the release at the end ({@link #finalizeBalance}), the closing of what a stopped
 * process left in the air — is ONE call into the engine. The engine is seed-prepaid's ({@code PrepaidEngine}: the base's money steps moved
 * there, the Prepaid builder on top); the kit finds its maker on the classpath ({@link MoneyEngineMaker}).
 *
 * <p><b>The two APIs (the owner, 2026-10-27: "two apis must: reserveBalance() and finalizeBalance()").</b> {@link #reserveBalance} at
 * admission — every tier the walk planned ({@link #planReserve}) held, or charged at once for a charge-now lane (SMS, ad), in ONE unit of the
 * ledger; {@link #reserveNextWindow} is the same reserve for the next window of a windowed lane (the call), asked by the base's budget child
 * — the live call's periodic {@code reserveBalance}. {@link #finalizeBalance} at the end — "for calls, we calculate call's actual charge,
 * return balance and apply the final charge. for sms, ad and other applications using charge now, may not do anything" — ONE unit too.
 * Every verb is idempotent per (session, tier) through the ledger port's references; every write goes through the kit's ONE ledger port.
 *
 * @param <C> the lane's context
 */
public interface MoneyEngine<C extends RoutesphereContext> {

    /**
     * ADMITTING, ONE tier as the chain walks it — the default of the lane's {@code reserveBalance}: rated by the lane, its first unit held
     * on its account through the ledger under the tier's reference, the tier recorded on the walk. The verdict: ok, or the cause.
     */
    Verdict planReserve(C ctx, Tier tier, Partner partner);

    /**
     * L2b item 2 · ADMITTING, after EVERY tier of the candidate passed its checks: the money of every tier the engine was asked to
     * {@link #planReserve} — rated and authorized as one, under the accounts' locks — held in ONE unit of the ledger (all tiers or none). Null =
     * held (or nothing to hold); else the cause, nothing held anywhere. Default: nothing (an engine that holds as the chain walks).
     */
    default String reserveBalance(C ctx) { return null; }

    /** ADMITTING · a refused candidate (the call switch's {@code compensateReserves}): every tier it held goes back, in the order held. */
    void release(C ctx, List<LevelAdmission> held, String why);

    /**
     * ACTIVE, one tick of the budget child: every tier renews its window; the narrowest answer, in seconds — the period = on; 0 = a tier
     * can pay nothing more; between = a partial window (the cut comes when it ends). Called under the session's money lock.
     */
    double reserveNextWindow(C ctx);

    /** ACTIVE · ONE tier's next window — the default of the lane's {@code renewWindowSeconds}: the seconds it funds. */
    double renewTier(C ctx, LevelAdmission tier);

    /** ACTIVE · a window of a lane's own ({@code reserveWindow}): {@code amount} held under {@code reference}. Null = held; else the cause. */
    String hold(C ctx, LevelAdmission tier, BigDecimal amount, String reference);

    /**
     * The final state, exactly once per session, under the session's money lock: answered — every tier finalized (the lane's
     * {@code finalizeBalance}); never answered — every reserve back, unless the refund rule charges a session offered and never consumed.
     * Writes {@code ctx.settlements}; a tier the ledger did not take is OWED.
     */
    void finalizeBalance(C ctx, Outcome outcome, boolean answered);

    /** The final state, ONE tier — the default of the lane's {@code finalizeBalance}: delivered → its charge, else the refund rule; settled. */
    TierSettlement settleTier(C ctx, LevelAdmission tier, Outcome outcome);

    /**
     * The start (R1-6, A2): a session HANDED OVER when the process died is charged what it reserved — each tier closed through the ledger
     * as settled at exactly its reserved sum. Answers {closed, owed}.
     */
    int[] closeAsReserved(SessionJournal.Leftover left);

    /** The start (F9): one reserve of a session never handed over, given back through the ledger. False = OWED (said with the reference). */
    boolean giveBackLeftover(String sessionId, SessionJournal.Held held);
}
