package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * L2b item 2 · The money of ONE candidate, planned as the chain walks it (design §10.3 P5: "every tier rated and authorized first, then
 * ONE batch — nothing to return"): per tier, the accounts that may pay (their locks are taken with the unit's) and its hold — the moved
 * hold of that tier, asked when every tier has passed its checks, inside the walk's one unit. Kept on the session for the candidate under
 * admission ({@code RoutesphereContext.moneyPlan}); a later candidate starts a new plan.
 */
final class WalkPlan {

    /** One tier: the accounts it may hold on, and its hold — null = held, else the cause. */
    record PlannedTier(List<LevelAdmission> accounts, Supplier<String> hold) {}

    private final int candidate;
    private final List<PlannedTier> tiers = new ArrayList<>();

    private WalkPlan(int candidate) { this.candidate = candidate; }

    /** The plan of the candidate under admission: the session's, or a new one for a new candidate. */
    static WalkPlan of(RoutesphereContext ctx) {
        if (ctx.moneyPlan instanceof WalkPlan plan && plan.candidate == ctx.candidatesTried) return plan;
        WalkPlan plan = new WalkPlan(ctx.candidatesTried);
        ctx.moneyPlan = plan;
        return plan;
    }

    /** The plan of the candidate under admission, taken off the session (null = nothing planned). */
    static WalkPlan takeFrom(RoutesphereContext ctx) {
        Object planned = ctx.moneyPlan;
        ctx.moneyPlan = null;
        return planned instanceof WalkPlan plan && plan.candidate == ctx.candidatesTried && !plan.tiers.isEmpty() ? plan : null;
    }

    void add(List<LevelAdmission> accounts, Supplier<String> hold) { tiers.add(new PlannedTier(List.copyOf(accounts), hold)); }

    List<PlannedTier> tiers() { return tiers; }

    /** Every account of every tier: the unit locks them all, once, in the global order, before the first hold. */
    List<LevelAdmission> accounts() {
        List<LevelAdmission> all = new ArrayList<>();
        for (PlannedTier tier : tiers) all.addAll(tier.accounts());
        return all;
    }
}
