package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.statewalk.session.Verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * ADMITTING, for every application: the candidates in order, and for each the call switch's admission — (c) the entry partner → (b) the
 * tiers leaf to root, per tier: check, authorize, root rules, (e) rate + RESERVE → (d) the route at the root → the last gate. A refused
 * candidate keeps nothing: every reserve goes back and its slot is free. The chain is the entry tenant's, and it must end at the session's
 * OWN root: a session never climbs another root's tree.
 *
 * <p>The work behind {@link RoutesphereFlow#admit}; the application's part of each step is a hook of the flow. Stateless: one per ask.
 * L2a · No money here: the tier's hold is the lane's {@code reserveBalance} (its default, the money engine's {@code admit}), and a refused
 * candidate's give-back is ONE call into the engine ({@code release}) — both moved to seed-prepaid ({@code TierReserve}). L2b: once every
 * tier passed, ONE call holds every tier's money at once ({@code reserveBalance}: one unit of the ledger, all tiers or none).
 */
final class AdmissionChain<C extends RoutesphereContext> {

    private final RoutesphereFlow<C> flow;

    private AdmissionChain(RoutesphereFlow<C> flow) { this.flow = flow; }

    static <C extends RoutesphereContext> AdmissionChain<C> of(RoutesphereFlow<C> flow) { return new AdmissionChain<>(flow); }

    // ── the candidates ──────────────────────────────────────────────────────

    Verdict admitFirstCandidate(C ctx) {
        openBudget(ctx);
        List<Candidate> candidates = ctx.candidates;
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            if (cutByBudget(ctx, candidate)) continue;
            String refusal = admitCandidate(ctx, candidate);
            if (refusal == null) return accept(ctx, candidate, index);
            ctx.lastRefusal = refusal;
        }
        ctx.trying = null;                                       // outside admission there is no candidate under admission
        return Verdict.reject(flow.safely(ctx, "rejectCause", () -> flow.rejectCause(ctx), SessionCause.INTERNAL_ERROR));
    }

    /** The candidates that pay share one budget: the ADMITTING timer minus the reserve kept for a free candidate. */
    private void openBudget(C ctx) {
        ctx.budgetSpent = false;
        ctx.admissionDeadlineMs = ctx.dryRun ? Long.MAX_VALUE : flow.kit().clock().millis() + flow.kit().settings().admissionBudgetMs();
    }

    /** A candidate that pays is not started when no time is left; a free one still is (it asks nothing of the ledger). */
    private boolean cutByBudget(C ctx, Candidate candidate) {
        boolean free = flow.safely(ctx, "isFree", () -> flow.isFree(ctx, candidate), false);
        return !free && flow.paidTimeIsOver(ctx);
    }

    private String admitCandidate(C ctx, Candidate candidate) {
        ctx.walking = new ArrayList<>();
        ctx.trying = candidate;
        ctx.candidatesTried++;
        String refusal;
        try {
            refusal = walkCandidate(ctx, candidate);
        } catch (RuntimeException e) {
            refusal = flow.internalError(ctx, "admission", e);
        }
        if (refusal != null) undo(ctx, refusal);
        return refusal;
    }

    private Verdict accept(C ctx, Candidate candidate, int index) {
        ctx.admitted = true;
        ctx.chosen = candidate;
        ctx.candidateIndex = index;
        ctx.trying = null;
        return Verdict.ok();
    }

    // ── one candidate: the call switch's runAdmission ───────────────────────

    private String walkCandidate(C ctx, Candidate candidate) {
        ctx.identificationCause = null;                          // P1-O: this candidate's own helper says why it found nobody
        Tier leaf = flow.step(ctx, "IDENTIFY_PARTNER", () -> flow.identifyPartner(ctx, candidate));
        String refusal = leaf == null ? admitWithoutPartner(ctx, candidate) : admitThroughTheTiers(ctx, leaf);
        if (refusal == null) refusal = flow.money().reserveBalance(ctx);
        if (refusal == null) refusal = routeAtTheRoot(ctx);
        if (refusal == null) refusal = confirm(ctx, candidate);
        return refusal;
    }

    /**
     * Only a free candidate may run with no partner: it has no tier and pays nothing. Any other is refused with the cause its helper recorded
     * (P1-O: {@code CREDENTIALS_REJECTED}), else {@code PARTNER_NOT_FOUND}.
     */
    private String admitWithoutPartner(C ctx, Candidate candidate) {
        if (flow.isFree(ctx, candidate)) return null;
        return ctx.identificationCause != null ? ctx.identificationCause : SessionCause.PARTNER_NOT_FOUND;
    }

    private String admitThroughTheTiers(C ctx, Tier leaf) {
        List<Tier> chain = flow.iterateResellerTenants(leaf);
        Tier root = chain.get(chain.size() - 1);
        if (leavesTheSessionsOwnTree(ctx, leaf, root)) return SessionCause.PARTNER_NOT_FOUND;
        keepTheEntry(ctx, leaf);
        for (Tier tier : chain) {
            Partner partner = tier.isLeaf() ? leaf.partner() : partnerOfTheTierAbove(ctx, tier);
            String refusal = causeOf(flow.performAdmission(ctx, tier, partner));
            if (refusal != null) return refusal;
        }
        return null;
    }

    private static void keepTheEntry(RoutesphereContext ctx, Tier leaf) {
        ctx.entryTenant = leaf.tenant();
        ctx.partner = leaf.partner();
        ctx.entryTenantDb = leaf.dbName();
        ctx.entryPartnerId = leaf.partner().getIdPartner();
    }

    /**
     * The belt of every application: a session climbs ITS root's tree and no other. An entry whose chain ends at another root (a lookup or
     * an override that searched across trees — a partner id is unique inside one tree only) is refused as if the partner did not exist.
     */
    private boolean leavesTheSessionsOwnTree(C ctx, Tier leaf, Tier root) {
        String own = ctx.tree == null ? null : ctx.tree.rootDbName();
        if (own == null || own.equals(root.dbName())) return false;
        RoutesphereFlow.log.error("[{}] {} | the entry partner {} was found in tenant '{}', a tier of the tree of '{}', but this session's root is '{}':"
            + " a session never leaves its own tree — refused {}", flow.name(), LogText.clean(ctx.sessionId()), leaf.partner().getIdPartner(), leaf.dbName(),
            root.dbName(), own, SessionCause.PARTNER_NOT_FOUND);
        ctx.history.note(flow.name(), "the entry partner was found in the tree of '" + root.dbName() + "', not in this session's own ('" + own + "')");
        return true;
    }

    private Partner partnerOfTheTierAbove(C ctx, Tier tier) {
        return flow.step(ctx, "PARTNER_ABOVE", () -> flow.partnerAbove(tier));
    }

    /**
     * One tier, in the call switch's order ({@code DefaultAdmissionService.admitAtLevel}): the partner is known and active → the checks that
     * take no slot → the leaf's channel slot → the checks after the slot → the root's rules → rated and reserved. The first refusal ends the
     * walk; the caller gives back everything taken before it.
     */
    String performAdmission(C ctx, Tier tier, Partner partner) {
        if (partner == null) return SessionCause.PARTNER_NOT_FOUND;
        String refusal = causeOf(flow.step(ctx, "CHECK_PARTNER", () -> flow.checkPartner(ctx, tier, partner)));
        if (refusal == null) refusal = causeOf(flow.step(ctx, "AUTHORIZE_BEFORE_SLOT", () -> flow.authorizeBeforeSlot(ctx, tier, partner)));
        if (refusal == null && tier.isLeaf()) refusal = takeChannelSlot(ctx, tier, partner);
        if (refusal == null) refusal = causeOf(flow.step(ctx, "AUTHORIZE", () -> flow.authorize(ctx, tier, partner)));
        if (refusal == null && tier.isRoot()) refusal = causeOf(flow.step(ctx, "ROOT_RULES", () -> flow.applyRootRules(ctx, tier, partner)));
        if (refusal == null) refusal = causeOf(flow.step(ctx, "RESERVE", () -> flow.reserveBalance(ctx, tier, partner)));
        return refusal;
    }

    private static String causeOf(Verdict verdict) {
        if (verdict == null) return SessionCause.INTERNAL_ERROR;
        return verdict.accepted() ? null : verdict.cause();
    }

    private String takeChannelSlot(C ctx, Tier tier, Partner partner) {
        if (ctx.dryRun) return null;
        return flow.kit().slots().acquire(ctx.sessionId(), tier.dbName(), partner) ? null : SessionCause.CHANNEL_LIMIT_REACHED;
    }

    // ── (d) the route at the root, then the last gate ───────────────────────

    /** C8 · The route is resolved at the root after every tier reserved (step (d)); no hop refuses this candidate with NO_ROUTE. */
    private String routeAtTheRoot(C ctx) {
        ctx.routePlan = null;
        RoutePlan plan = flow.planOf(ctx);
        if (plan != null && !plan.isEmpty()) return null;
        ctx.routePlan = null;
        return SessionCause.NO_ROUTE;
    }

    private String confirm(C ctx, Candidate candidate) {
        ctx.levels = List.copyOf(ctx.walking);
        return causeOf(flow.step(ctx, "CONFIRM_ADMISSION", () -> flow.confirmAdmission(ctx, candidate)));
    }

    // ── giving it back ──────────────────────────────────────────────────────

    /** The call switch's compensateReserves: a refused candidate gives every reserve back and frees its slot. */
    private void undo(C ctx, String cause) {
        ctx.levels = List.of();
        ctx.routePlan = null;
        if (ctx.dryRun) return;
        flow.money().release(ctx, ctx.walking, cause);
        flow.kit().slots().release(ctx.sessionId());
        ctx.history.note(flow.name(), "candidate " + ctx.candidatesTried + " refused: " + cause);
    }

    static String rootDbOf(RoutesphereContext ctx) { return ctx.tree == null ? ctx.root : ctx.tree.rootDbName(); }
}
