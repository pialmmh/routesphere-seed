package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.MoneyRules;
import com.telcobright.statewalk.session.Outcome;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * L2a · The lane's answers as the money engine reads them (design {@code prepaid-engine-design.md} §11): every question of
 * {@link RoutesphereFlow} the money asks — the lane's overridable hooks — and the base's guards, through ONE public door
 * ({@link RoutesphereFlow#answers()}), so an engine outside the base's package (seed-prepaid's) calls the lane's {@code protected} answers
 * while the base moves no money itself. Each answer is the flow's own method, unchanged.
 *
 * @param <C> the lane's context
 */
public interface LaneAnswers<C extends RoutesphereContext> {

    /** The lane's one name (statewalk's table, the log lines). */
    String name();

    /** What the host handed the lane: the ledger port, the journal, the clock, the settings, the counters. */
    RoutesphereKit kit();

    /** The lane's own money rules (seed-prepaid's Prepaid), or null: the engine runs on these answers. */
    MoneyRules<C> moneyRules();

    /** ADMITTING · this candidate costs nothing (the house ad). */
    boolean isFree(C ctx, Candidate candidate);

    /** ADMITTING, every tier ■ · what this tier's partner pays and how much admission reserves. Null = no rate ({@code UNRATED}). */
    TierRate rateAtLevel(C ctx, Tier tier, Partner partner);

    /** ADMITTING · the cause when a tier cannot pay. */
    String noBalanceCause(C ctx);

    /** What is left of this session's admission budget, in milliseconds (a dry run: {@code Long.MAX_VALUE}). */
    long admissionTimeLeftMs(C ctx);

    /** ACTIVE, every renewal · what this tier reserves for the next window. Null = this tier does not renew. */
    TierRate rateNextWindow(C ctx, LevelAdmission tier);

    /** ACTIVE, every renewal · the lane's renewal of one tier (its default is the engine's {@code renewTier}). */
    double renewWindowSeconds(C ctx, LevelAdmission tier);

    /** ACTIVE · the next account to carry the session on when a tier's account could not fund the next window; null = the money ended. */
    Long nextAccount(C ctx, LevelAdmission tier);

    /** The final state ■ · did the service happen? */
    boolean delivered(C ctx, Outcome outcome);

    /** The final state, delivered · what this tier is charged (rate × billed units, or its whole reserve). */
    BigDecimal chargeAtSettle(C ctx, LevelAdmission tier);

    /** The final state · what the session came to, for the refund rule. */
    SettleOutcome settleOutcome(C ctx);

    /** The final state ■ · the lane's refund rule. Null = the call's. */
    RefundPolicy refundPolicy(C ctx);

    /** The settlement, first · the billed duration in seconds. */
    double billedDuration(C ctx, long nowMs);

    /** The final state · the lane's finalize of one tier (its default is the engine's {@code settleTier}). */
    TierSettlement finalizeBalance(C ctx, LevelAdmission tier, Outcome outcome);

    /** The call's records as the next start would publish them if this process died now (the journal of the sessions in the air). */
    String lostRecordsOf(C ctx);

    /** The database name of the session's root (its snapshot's, else the root's code): the journal's and the CDR road's root. */
    String rootDbOf(C ctx);

    /** Run one named step of the session (timed into the history when traced). */
    <T> T step(C ctx, String stepName, Supplier<T> body);

    /** Run a step of the lane that must never break the session: a throw is logged and {@code whenItThrows} is the answer. */
    <T> T safely(C ctx, String stepName, Supplier<T> body, T whenItThrows);
}
