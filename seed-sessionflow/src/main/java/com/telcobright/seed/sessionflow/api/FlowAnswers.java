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
 * L2a · The lane's answers as the money engine reads them ({@link RoutesphereFlow#answers()}): each method is ONE call to the flow's own
 * hook or guard, unchanged — the lane's override when it wrote one, else the base's default. Nothing of its own; one per ask.
 */
final class FlowAnswers<C extends RoutesphereContext> implements LaneAnswers<C> {

    private final RoutesphereFlow<C> flow;

    FlowAnswers(RoutesphereFlow<C> flow) { this.flow = flow; }

    @Override public String name() { return flow.name(); }
    @Override public RoutesphereKit kit() { return flow.kit(); }
    @Override public MoneyRules<C> moneyRules() { return flow.moneyRules(); }
    @Override public boolean isFree(C ctx, Candidate candidate) { return flow.isFree(ctx, candidate); }
    @Override public TierRate rateAtLevel(C ctx, Tier tier, Partner partner) { return flow.rateAtLevel(ctx, tier, partner); }
    @Override public String noBalanceCause(C ctx) { return flow.noBalanceCause(ctx); }
    @Override public long admissionTimeLeftMs(C ctx) { return flow.admissionTimeLeftMs(ctx); }
    @Override public TierRate rateNextWindow(C ctx, LevelAdmission tier) { return flow.rateNextWindow(ctx, tier); }
    @Override public double renewWindowSeconds(C ctx, LevelAdmission tier) { return flow.renewWindowSeconds(ctx, tier); }
    @Override public Long nextAccount(C ctx, LevelAdmission tier) { return flow.nextAccount(ctx, tier); }
    @Override public boolean delivered(C ctx, Outcome outcome) { return flow.delivered(ctx, outcome); }
    @Override public BigDecimal chargeAtSettle(C ctx, LevelAdmission tier) { return flow.chargeAtSettle(ctx, tier); }
    @Override public SettleOutcome settleOutcome(C ctx) { return flow.settleOutcome(ctx); }
    @Override public RefundPolicy refundPolicy(C ctx) { return flow.refundPolicy(ctx); }
    @Override public double billedDuration(C ctx, long nowMs) { return flow.billedDuration(ctx, nowMs); }
    @Override public TierSettlement finalizeTier(C ctx, LevelAdmission tier, Outcome outcome) { return flow.finalizeTier(ctx, tier, outcome); }
    @Override public String lostRecordsOf(C ctx) { return SessionCdr.of(flow).lostRecordsOf(ctx); }
    @Override public String rootDbOf(C ctx) { return AdmissionChain.rootDbOf(ctx); }
    @Override public <T> T step(C ctx, String stepName, Supplier<T> body) { return flow.step(ctx, stepName, body); }
    @Override public <T> T safely(C ctx, String stepName, Supplier<T> body, T whenItThrows) { return flow.safely(ctx, stepName, body, whenItThrows); }
}
