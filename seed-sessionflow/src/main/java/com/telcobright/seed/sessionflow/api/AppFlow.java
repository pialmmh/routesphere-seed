package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.seed.sessionflow.dependencies.RefundPolicy;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.MoneyRules;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.ServiceEnd;
import com.telcobright.statewalk.session.events.SignalingProgress;

import java.math.BigDecimal;
import java.util.List;

/**
 * The flow {@link RoutesphereApp} builds: every question the base asks is answered by the lane's lambda, or — for an optional hook the
 * lane did not give — by the base's own default (the call's). Its only state is the kit and the sealed answers: the lane's rules, shared by
 * every session (the pool rule).
 */
final class AppFlow<C extends RoutesphereContext> extends RoutesphereFlow<C> {

    private final RoutesphereApp.Builder<C> app;

    AppFlow(RoutesphereKit kit, RoutesphereApp.Builder<C> app) {
        super(kit);
        this.app = app;
    }

    // ── the forced answers ──

    @Override public String name() { return app.name; }
    @Override protected int serviceGroup(C ctx) { return app.serviceGroup; }
    @Override protected String identifyRootLevelTenant(RoutesphereFacts facts) { return app.identifyRootLevelTenant.identify(facts, kit.lookups()); }
    @Override protected Tier identifyPartner(C ctx, Candidate candidate) { return app.identifyPartner.identify(ctx, candidate, kit.lookups()); }
    @Override
    protected TierRate rateAtLevel(C ctx, Tier tier, Partner partner) {
        if (app.rateAtLevel == null) throw new IllegalStateException("[" + app.name + "] the lane's Prepaid rates every tier: rateAtLevel is not asked of it");
        return app.rateAtLevel.rate(ctx, tier, partner);
    }
    @Override protected RoutePlan route(C ctx) { return app.route.apply(ctx); }
    @Override protected boolean delivered(C ctx, Outcome outcome) { return app.delivered.test(ctx, outcome); }
    @Override protected RefundPolicy refundPolicy(C ctx) { return app.refundPolicy == null ? null : app.refundPolicy.apply(ctx); }
    @Override protected RerouteAction rerouteActionFor(String cause) { return app.rerouteActionFor.apply(cause); }

    // ── the optional hooks: the lane's lambda, else the base's default ──

    /** L2a · The lane's own Prepaid, or null (the engine runs on the answers above). */
    @Override protected MoneyRules<C> moneyRules() { return app.prepaid; }

    @Override protected String buildTask(C ctx) { return app.buildTask == null ? super.buildTask(ctx) : app.buildTask.apply(ctx); }

    @Override
    protected List<Candidate> selectCandidates(C ctx) { return app.selectCandidates == null ? super.selectCandidates(ctx) : app.selectCandidates.apply(ctx); }

    @Override
    protected Verdict authorize(C ctx, Tier tier, Partner partner) {
        return app.authorize == null ? super.authorize(ctx, tier, partner) : app.authorize.check(ctx, tier, partner);
    }

    @Override
    protected Verdict applyRootRules(C ctx, Tier root, Partner rootPartner) {
        return app.applyRootRules == null ? super.applyRootRules(ctx, root, rootPartner) : app.applyRootRules.check(ctx, root, rootPartner);
    }

    @Override
    protected Verdict confirmAdmission(C ctx, Candidate candidate) {
        return app.confirmAdmission == null ? super.confirmAdmission(ctx, candidate) : app.confirmAdmission.apply(ctx, candidate);
    }

    @Override
    protected SettleOutcome settleOutcome(C ctx) { return app.settleOutcome == null ? super.settleOutcome(ctx) : app.settleOutcome.apply(ctx); }

    @Override protected BigDecimal billedUnits(C ctx) { return app.billedUnits == null ? super.billedUnits(ctx) : app.billedUnits.apply(ctx); }

    @Override
    protected TierRate rateNextWindow(C ctx, LevelAdmission tier) {
        return app.rateNextWindow == null ? super.rateNextWindow(ctx, tier) : app.rateNextWindow.apply(ctx, tier);
    }

    @Override
    protected void onSignalingProgress(C ctx, SignalingProgress progress) { if (app.onSignalingProgress != null) app.onSignalingProgress.accept(ctx, progress); }

    @Override protected void onAnswered(C ctx, Established answer) { if (app.onAnswered != null) app.onAnswered.accept(ctx, answer); }

    @Override protected void onServiceEnded(C ctx, ServiceEnd end) { if (app.onServiceEnded != null) app.onServiceEnded.accept(ctx, end); }

    @Override protected void fillCdr(C ctx, LevelAdmission tier, CdrEvent cdr) { if (app.fillCdr != null) app.fillCdr.fill(ctx, tier, cdr); }

    @Override protected Object buildSdr(C ctx, Outcome outcome) { return app.buildSdr == null ? super.buildSdr(ctx, outcome) : app.buildSdr.apply(ctx, outcome); }

    @Override protected void onEnded(C ctx, Outcome outcome) { if (app.onEnded != null) app.onEnded.accept(ctx, outcome); }

    @Override protected List<String> topicsOf(String root) { return app.topicsOf == null ? super.topicsOf(root) : app.topicsOf.apply(root); }
}
