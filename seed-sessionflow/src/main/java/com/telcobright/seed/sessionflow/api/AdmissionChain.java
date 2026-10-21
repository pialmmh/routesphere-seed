package com.telcobright.seed.sessionflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import com.telcobright.statewalk.session.Verdict;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ADMITTING, for every application: the candidates in order, and for each the call switch's admission — (c) the entry partner → (b) the
 * tiers leaf to root, per tier: check, authorize, root rules, (e) rate + RESERVE → (d) the route at the root → the last gate. A refused
 * candidate keeps nothing: every reserve goes back and its slot is free. The chain is the entry tenant's, and it must end at the session's
 * OWN root: a session never climbs another root's tree.
 *
 * <p>The work behind {@link RoutesphereFlow#admit}; the application's part of each step is a hook of the flow. Stateless: one per ask.
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
        Tier leaf = flow.step(ctx, "IDENTIFY_ENTRY_PARTNER", () -> flow.identifyEntryPartner(ctx, candidate));
        String refusal = leaf == null ? admitWithoutPartner(ctx, candidate) : admitThroughTheTiers(ctx, leaf);
        if (refusal == null) refusal = routeAtTheRoot(ctx);
        if (refusal == null) refusal = confirm(ctx, candidate);
        return refusal;
    }

    /** Only a free candidate may run with no partner: it has no tier and pays nothing. */
    private String admitWithoutPartner(C ctx, Candidate candidate) {
        return flow.isFree(ctx, candidate) ? null : SessionCause.PARTNER_NOT_FOUND;
    }

    private String admitThroughTheTiers(C ctx, Tier leaf) {
        List<Tier> chain = flow.iterateResellerTenants(leaf);
        Tier root = chain.get(chain.size() - 1);
        if (leavesTheSessionsOwnTree(ctx, leaf, root)) return SessionCause.PARTNER_NOT_FOUND;
        keepTheEntry(ctx, leaf);
        for (Tier tier : chain) {
            Partner partner = tier.isLeaf() ? leaf.partner() : partnerAbove(ctx, tier);
            String refusal = admitAtLevel(ctx, tier, partner);
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
            + " a session never leaves its own tree — refused {}", flow.name(), ctx.sessionId(), leaf.partner().getIdPartner(), leaf.dbName(),
            root.dbName(), own, SessionCause.PARTNER_NOT_FOUND);
        ctx.history.note(flow.name(), "the entry partner was found in the tree of '" + root.dbName() + "', not in this session's own ('" + own + "')");
        return true;
    }

    private Partner partnerAbove(C ctx, Tier tier) {
        return flow.step(ctx, "IDENTIFY_PARTNER", () -> flow.identifyPartner(tier));
    }

    /**
     * One tier, in the call switch's order ({@code DefaultAdmissionService.admitAtLevel}): the partner is known and active → the checks that
     * take no slot → the leaf's channel slot → the checks after the slot → the root's rules → rated and reserved. The first refusal ends the
     * walk; the caller gives back everything taken before it.
     */
    private String admitAtLevel(C ctx, Tier tier, Partner partner) {
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

    // ── (e) the reserve ─────────────────────────────────────────────────────

    /**
     * The default of {@code reserveBalance}: the tier is rated by the application and reserved by the base — or, when the application's own
     * step did both in one move ({@link TierRate#held}), taken as that step left it. A rating hook may refuse in the ledger's own words.
     */
    Verdict rateAndReserve(C ctx, Tier tier, Partner partner) {
        TierRate rate;
        try {
            rate = flow.isFree(ctx, ctx.trying) ? TierRate.free() : flow.step(ctx, "RATE", () -> flow.rateAtLevel(ctx, tier, partner));
        } catch (LedgerPort.LedgerRefusal refused) {
            return Verdict.reject(refused.code());
        } catch (LedgerPort.LedgerFault fault) {
            return Verdict.reject(ledgerFault(ctx, tier.levelIndex(), tier.dbName(), partner.getIdPartner(), fault));
        }
        if (rate == null) return Verdict.reject(SessionCause.UNRATED);
        if (rate.held() != null) return takeHeld(ctx, rate.held(), tier.levelIndex());
        LevelAdmission level = levelOf(tier.tenant(), partner, tier.levelIndex(), rate);
        String refusal = ctx.dryRun ? null
            : reserve(ctx, level, rate.reserveAmount(), referenceOf(ctx, tier.levelIndex()), flow.noBalanceCause(ctx), flow.admissionTimeLeftMs(ctx));
        if (refusal != null) return Verdict.reject(refusal);
        ctx.walking.add(level);
        return Verdict.ok();
    }

    /** The application's own step admitted the tier in one move: the base takes its level, names its reference, asks its ledger nothing. */
    private Verdict takeHeld(C ctx, LevelAdmission held, int levelIndex) {
        if (held.getDebitReference() == null) held.setDebitReference(referenceOf(ctx, levelIndex));
        ctx.walking.add(held);
        if (!ctx.dryRun && SessionSettlement.holdsReserve(held)) journalReserve(ctx, held, TierSettlement.reservedOf(held));
        return Verdict.ok();
    }

    private static LevelAdmission levelOf(Tenant tier, Partner partner, int levelIndex, TierRate rate) {
        LevelAdmission level = new LevelAdmission(levelIndex, tier, partner, rate.account());
        level.setRate(rate.rate());
        if (rate.uom() != null) level.setUom(rate.uom());
        level.setRatePrefix(rate.ratePrefix());
        level.setUsageKind(rate.usageKind());
        level.setUsageSeconds(rate.usageSeconds());
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    /**
     * §L.1 · Every money call carries the session and the tier: the first candidate reserves under {@code <session>#L<tier>}, a later one
     * under {@code <session>#<try>#L<tier>} — a second reserve, settle or release of the same (session, tier) moves nothing more.
     */
    static String referenceOf(RoutesphereContext ctx, int levelIndex) {
        int tryNo = Math.max(1, ctx.candidatesTried);
        return (tryNo <= 1 ? ctx.sessionId() : ctx.sessionId() + "#" + tryNo) + "#L" + levelIndex;
    }

    /** The ledger's reserve, inside what is left of the budget. Null = held (or nothing to hold). A ledger fault is never a balance cause. */
    private String reserve(C ctx, LevelAdmission level, BigDecimal amount, String reference, String causeWhenItCannotPay, long withinMs) {
        if (amount.signum() <= 0) return null;
        if (withinMs <= 0) return notAsked(ctx, level);
        try {
            Optional<LedgerPort.Reservation> held = flow.kit().ledger().reserve(level, amount, reference, withinMs);
            if (held.isEmpty()) return causeWhenItCannotPay;
            recordReserve(level, held.get(), reference);
            journalReserve(ctx, level, held.get().reserved());
            return null;
        } catch (LedgerPort.LedgerRefusal refused) {
            return refused.code();
        } catch (LedgerPort.LedgerFault fault) {
            return ledgerFault(ctx, level.getLevelIndex(), level.getDbName(), level.getPartnerId(), fault);
        }
    }

    private static void recordReserve(LevelAdmission level, LedgerPort.Reservation held, String reference) {
        boolean first = level.getDebitReference() == null;
        if (first) level.setDebitReference(reference);
        if (held.account() != null) level.setChargeAccountId(held.account());
        if (held.uom() != null) level.setUom(held.uom());
        if (first) {
            level.setReservedAmount(held.reserved());
            level.setBalanceBefore(held.balanceBefore());
        } else {
            level.addToTotalReserved(held.reserved());
        }
        level.setBalanceAfter(held.balanceAfter());
        level.incrementReservationCount();
    }

    private String notAsked(C ctx, LevelAdmission level) {
        ctx.budgetSpent = true;
        ctx.history.note(flow.name(), "tier " + level.getLevelIndex() + " (" + level.getDbName() + ") was not reserved: no time left — the ledger was not asked");
        return SessionCause.ADMISSION_TIMEOUT;
    }

    private String ledgerFault(C ctx, int levelIndex, String dbName, Integer partnerId, RuntimeException fault) {
        ctx.systemFault = SessionCause.BILLING_SYSTEM_ERROR;
        RoutesphereFlow.log.error("[{}] {} | LEDGER FAULT at tier {} ({}) partner {}: {} — this is NOT a balance case", flow.name(), ctx.sessionId(),
            levelIndex, dbName, partnerId, fault.getMessage());
        return SessionCause.BILLING_SYSTEM_ERROR;
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
        for (LevelAdmission level : ctx.walking) release(ctx, level, cause);
        flow.kit().slots().release(ctx.sessionId());
        ctx.history.note(flow.name(), "candidate " + ctx.candidatesTried + " refused: " + cause);
    }

    private void release(C ctx, LevelAdmission level, String why) {
        if (!SessionSettlement.holdsReserve(level)) return;
        try {
            flow.kit().ledger().release(level, why);
            journalRelease(ctx, level);
        } catch (RuntimeException e) {
            flow.kit().counters().owed.incrementAndGet();
            RoutesphereFlow.log.error("[{}] {} | OWED: the reserve {} of {} {} at tier {} ({}) partner {} was NOT released ({}): {}", flow.name(),
                ctx.sessionId(), level.getDebitReference(), level.getTotalReserved(), level.getUom(), level.getLevelIndex(),
                level.getDbName(), level.getPartnerId(), why, e.toString());
        }
    }

    // ── F9 · the journal of the sessions in the air takes every reserve as it is held ──────────

    private void journalReserve(C ctx, LevelAdmission level, BigDecimal amount) {
        SessionJournal journal = flow.kit().journal();
        if (!journal.keeps()) return;
        try {
            SessionJournal.Held held = new SessionJournal.Held(level.getLevelIndex(), rootDbOf(ctx), level.getDbName(), level.getPartnerId(),
                level.getPackageAccountId(), level.getUom(), amount, level.getDebitReference());
            journal.reserved(ctx.sessionId(), flow.kit().clock().millis(), SessionCdr.of(flow).lostRecordsOf(ctx), held);
        } catch (RuntimeException e) {
            flow.kit().counters().journalRefused.incrementAndGet();
            ctx.history.note(flow.name(), "the reserve " + level.getDebitReference() + " could not be written to the journal of the sessions in the air ("
                + journal.where() + "): " + e.getMessage());
        }
    }

    void journalRelease(C ctx, LevelAdmission level) {
        SessionJournal journal = flow.kit().journal();
        if (!journal.keeps() || level.getDebitReference() == null) return;
        try {
            journal.released(ctx.sessionId(), level.getDebitReference());
        } catch (RuntimeException e) {
            RoutesphereFlow.log.warn("[{}] {} | the journal did not take the release of {} (the ledger moves money once per reference): {}",
                flow.name(), ctx.sessionId(), level.getDebitReference(), e.toString());
        }
    }

    static String rootDbOf(RoutesphereContext ctx) { return ctx.tree == null ? ctx.root : ctx.tree.rootDbName(); }

    // ── a long session renews its reserve ───────────────────────────────────

    /** A renewal runs while the session is answered, outside any admission: the ledger's own timeout bounds it. */
    private static final long RENEWAL_HAS_NO_BUDGET = Long.MAX_VALUE;

    /**
     * The default renewal of one tier (C14), in seconds: the next window as the application rates it, held under {@code …#W<n>}. The period =
     * held, or nothing to hold; 0 = refused (unless the next account takes over). A ledger FAULT never cuts a session.
     */
    double renewThroughLedger(C ctx, LevelAdmission level) {
        double period = flow.kit().settings().reservePeriodSec();
        TierRate next = flow.rateNextWindow(ctx, level);
        if (next == null || !next.reserves() || level.getDebitReference() == null) return period;
        String reference = level.getDebitReference() + "#W" + (level.getReservationCount() + 1);
        String refusal = reserve(ctx, level, next.reserveAmount(), reference, SessionCause.BALANCE_EXHAUSTED, RENEWAL_HAS_NO_BUDGET);
        if (refusal == null || SessionCause.BILLING_SYSTEM_ERROR.equals(refusal)) return period;
        return SpanRotation.of(flow).rotateOrCut(ctx, level, next) ? period : 0;
    }

    /** A renewal's reserve under its own reference (a lane's own window, the rotation's first window). Null = held. */
    String reserveForRenewal(C ctx, LevelAdmission level, BigDecimal amount, String reference) {
        return reserve(ctx, level, amount, reference, SessionCause.BALANCE_EXHAUSTED, RENEWAL_HAS_NO_BUDGET);
    }
}
