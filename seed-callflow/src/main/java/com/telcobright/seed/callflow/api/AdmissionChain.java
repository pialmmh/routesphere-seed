package com.telcobright.seed.callflow.api;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.spi.LedgerPort;
import com.telcobright.statewalk.pipeline.StepMode;
import com.telcobright.statewalk.session.AdmissionVerdict;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ADMITTING, for every application: the candidates in order, and for each the call switch's admission —
 * identify the entry partner → walk the tenant chain leaf to root (check, slot, authorize, root rules, rate, RESERVE)
 * → resolve the route → confirm. A refused candidate keeps nothing: every reserve goes back and its slot is free.
 * The chain is the entry tenant's, and it must end at the call's own tenant: a call never climbs another served tree.
 *
 * <p>This is the work behind {@link CallFlow#admit}; the application's part of each step is in {@link CallFlowSteps}.
 */
final class AdmissionChain<C extends CallFlowContext> {

    private final CallFlow<C> flow;

    AdmissionChain(CallFlow<C> flow) { this.flow = flow; }

    /** The working state of one candidate's walk: the tiers reserved so far. It never leaves the walk. */
    private static final class Walk {
        final int tryNo;
        final StepMode mode;
        final List<LevelAdmission> levels = new ArrayList<>();
        Tenant root;

        Walk(int tryNo, StepMode mode) { this.tryNo = tryNo; this.mode = mode; }

        boolean simulated() { return mode == StepMode.SIMULATE; }
    }

    // ── the candidates ──────────────────────────────────────────────────────

    AdmissionVerdict admitFirstCandidate(C ctx, StepMode mode) {
        openBudget(ctx, mode);
        int candidates = flow.safely(ctx, "candidateCount", () -> flow.candidateCount(ctx), 0);
        for (int index = 0; index < candidates; index++) {
            if (!eligible(ctx, index)) continue;
            if (cutByBudget(ctx)) continue;
            String refusal = admitCandidate(ctx, mode);
            if (refusal == null) return accept(ctx, index);
            ctx.lastRefusal = refusal;
        }
        return AdmissionVerdict.reject(flow.safely(ctx, "rejectCause", () -> flow.rejectCause(ctx), CallCause.INTERNAL_ERROR));
    }

    private boolean eligible(C ctx, int index) {
        return flow.safely(ctx, "useCandidate", () -> flow.useCandidate(ctx, index), false);
    }

    // ── the budget: admission has ONE deadline, inside the ADMITTING state's own ───────────────

    /** The candidates that pay share one budget: the state's deadline minus the reserve kept for a free candidate. A dry run has none. */
    private void openBudget(C ctx, StepMode mode) {
        ctx.budgetSpent = false;
        ctx.dryRun = mode == StepMode.SIMULATE;
        ctx.admissionDeadlineMs = mode == StepMode.SIMULATE ? Long.MAX_VALUE
            : flow.kit().clock().millis() + flow.kit().settings().admissionBudgetMs();
    }

    /** A candidate that pays is not started when no time is left; a free one still is (it asks nothing of the ledger). */
    private boolean cutByBudget(C ctx) {
        boolean free = flow.safely(ctx, "isFree", () -> flow.isFree(ctx), false);
        return !free && flow.paidTimeIsOver(ctx);
    }

    private String admitCandidate(C ctx, StepMode mode) {
        Walk walk = new Walk(++ctx.candidatesTried, mode);
        String refusal;
        try {
            refusal = walkCandidate(ctx, walk);
        } catch (RuntimeException e) {
            refusal = flow.internalError(ctx, "admission", e);
        }
        if (refusal != null) undo(ctx, walk, refusal);
        return refusal;
    }

    private AdmissionVerdict accept(C ctx, int index) {
        ctx.admitted = true;
        ctx.candidateIndex = index;
        return AdmissionVerdict.accept(index);
    }

    // ── one candidate: the call switch's runAdmission ───────────────────────

    private String walkCandidate(C ctx, Walk walk) {
        EntryPartner entry = flow.step(ctx, "IDENTIFY_ENTRY_PARTNER", () -> flow.identifyEntryPartner(ctx));
        String refusal = entry == null ? admitWithoutPartner(ctx) : admitThroughTenantChain(ctx, entry, walk);
        if (refusal == null) refusal = flow.step(ctx, "RESOLVE_ROUTE", () -> flow.resolveRoute(ctx, walk.root));
        if (refusal == null) refusal = confirm(ctx, walk);
        return refusal;
    }

    /** Only a free call may run with no partner: it has no tier and pays nothing. */
    private String admitWithoutPartner(C ctx) {
        return flow.isFree(ctx) ? null : CallCause.PARTNER_NOT_FOUND;
    }

    private String admitThroughTenantChain(C ctx, EntryPartner entry, Walk walk) {
        List<Tenant> chain = entry.tenant().getAncestorChain();
        Tenant root = chain.get(chain.size() - 1);
        if (leavesTheCallsOwnTree(ctx, entry, root)) return CallCause.PARTNER_NOT_FOUND;
        ctx.entryTenant = entry.tenant();
        ctx.partner = entry.partner();
        walk.root = root;
        for (int i = 0; i < chain.size(); i++) {
            Partner partner = i == 0 ? entry.partner() : partnerAbove(ctx, chain.get(i - 1), chain.get(i));
            String refusal = admitAtLevel(ctx, walk, chain.get(i), partner, i);
            if (refusal != null) return refusal;
        }
        return null;
    }

    /**
     * The belt of every application: a call that names its tenant climbs THAT tenant's tree and no other. An entry whose chain ends at
     * another root — a lookup or an override that searched across the trees this process serves (a partner id is unique inside one tree
     * only) — is refused as if the partner did not exist: for the call's own tenant it does not. Nothing of the call is put on the
     * foreign tier: no reserve, no slot, and its record stays on its own tenant.
     */
    private boolean leavesTheCallsOwnTree(C ctx, EntryPartner entry, Tenant root) {
        if (ctx.tenantName == null || ctx.tenantName.equals(root.getDbName())) return false;
        flow.log.error("[{}] {} | the entry partner {} was found in tenant '{}', a tier of the tree of '{}', but this call's tenant is '{}':"
            + " a call never leaves its own tree — refused {}", flow.name(), ctx.sessionKey, entry.partner().getIdPartner(),
            entry.tenant().getDbName(), root.getDbName(), ctx.tenantName, CallCause.PARTNER_NOT_FOUND);
        ctx.history.note(flow.name(), "the entry partner was found in the tree of '" + root.getDbName() + "', not in this call's own ('" + ctx.tenantName + "')");
        return true;
    }

    private Partner partnerAbove(C ctx, Tenant childTier, Tenant tier) {
        return flow.step(ctx, "IDENTIFY_PARTNER", () -> flow.identifyPartner(ctx, childTier, tier));
    }

    /** One tier: the partner is known and active, holds its slot, passes the tier's rules, is rated and reserved. */
    private String admitAtLevel(C ctx, Walk walk, Tenant tier, Partner partner, int levelIndex) {
        if (partner == null) return CallCause.PARTNER_NOT_FOUND;
        String refusal = flow.step(ctx, "CHECK_PARTNER", () -> flow.checkPartner(ctx, tier, partner, levelIndex));
        if (refusal == null && levelIndex == 0) refusal = takeChannelSlot(ctx, walk, tier, partner);
        if (refusal == null) refusal = flow.step(ctx, "AUTHORIZE", () -> flow.authorize(ctx, tier, partner, levelIndex));
        if (refusal == null && tier == walk.root) refusal = flow.step(ctx, "ROOT_RULES", () -> flow.applyRootRules(ctx, tier, partner));
        if (refusal == null) refusal = rateAndReserve(ctx, walk, tier, partner, levelIndex);
        return refusal;
    }

    private String takeChannelSlot(C ctx, Walk walk, Tenant tier, Partner partner) {
        if (walk.simulated()) return null;
        return flow.slots().acquire(ctx.sessionKey, tier.getDbName(), partner) ? null : CallCause.CHANNEL_LIMIT_REACHED;
    }

    /**
     * The tier is rated by the application and reserved by the base — or, when the application's own step did both in one move
     * ({@link TierRate#held}), taken as that step left it. A rating hook may refuse with the ledger's own words: a
     * {@link LedgerPort.LedgerRefusal} is the cause, a {@link LedgerPort.LedgerFault} is {@code BILLING_SYSTEM_ERROR}, never a balance case.
     */
    private String rateAndReserve(C ctx, Walk walk, Tenant tier, Partner partner, int levelIndex) {
        TierRate rate;
        try {
            rate = flow.isFree(ctx) ? TierRate.free() : flow.step(ctx, "RATE", () -> flow.rateAtLevel(ctx, tier, partner, levelIndex));
        } catch (LedgerPort.LedgerRefusal refused) {
            return refused.code();
        } catch (LedgerPort.LedgerFault fault) {
            return ledgerFault(ctx, levelIndex, tier.getDbName(), partner.getIdPartner(), fault);
        }
        if (rate == null) return CallCause.UNRATED;
        if (rate.held() != null) return takeHeld(ctx, walk, rate.held(), levelIndex);
        LevelAdmission level = levelOf(tier, partner, levelIndex, rate);
        String refusal = walk.simulated() ? null
            : reserve(ctx, level, rate.reserveAmount(), referenceOf(ctx, walk.tryNo, levelIndex), flow.noBalanceCause(ctx), flow.admissionTimeLeftMs(ctx));
        if (refusal == null) walk.levels.add(level);
        return refusal;
    }

    /**
     * The application's own step admitted the tier (the call switch's C6: {@code ReserveBalanceStep} rates, chooses the account —
     * package minutes before money, the reserve being the affordability test — and holds one unit, all in one move): the base takes
     * its level as the tier's, names the reference the settlement and a release will use, and asks its ledger nothing for it.
     */
    private String takeHeld(C ctx, Walk walk, LevelAdmission held, int levelIndex) {
        if (held.getDebitReference() == null) held.setDebitReference(referenceOf(ctx, walk.tryNo, levelIndex));
        walk.levels.add(held);
        return null;
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

    private String confirm(C ctx, Walk walk) {
        ctx.levels = List.copyOf(walk.levels);
        return flow.step(ctx, "CONFIRM_ADMISSION", () -> flow.confirmAdmission(ctx, walk.mode));
    }

    // ── the reserve, and giving it back ─────────────────────────────────────

    /** The first candidate reserves under {@code <call>#L<tier>}; a later one under {@code <call>#<try>#L<tier>}, never a replay. */
    private static String referenceOf(CallFlowContext ctx, int tryNo, int levelIndex) {
        return (tryNo <= 1 ? ctx.sessionKey : ctx.sessionKey + "#" + tryNo) + "#L" + levelIndex;
    }

    /**
     * The ledger's reserve, inside what is left of the budget. Null = held (or nothing to hold). A ledger fault is never a
     * balance cause; with no time left the ledger is not asked at all.
     */
    private String reserve(C ctx, LevelAdmission level, BigDecimal amount, String reference, String causeWhenItCannotPay, long withinMs) {
        if (amount.signum() <= 0) return null;
        if (withinMs <= 0) return notAsked(ctx, level);
        try {
            Optional<LedgerPort.Reservation> held = flow.kit().ledger().reserve(level, amount, reference, withinMs);
            if (held.isEmpty()) return causeWhenItCannotPay;
            recordReserve(level, held.get(), reference);
            return null;
        } catch (LedgerPort.LedgerRefusal refused) {
            return refused.code();
        } catch (LedgerPort.LedgerFault fault) {
            return ledgerFault(ctx, level, fault);
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

    /** No time is left for this tier's reserve: the ledger is not asked, the candidate ends here and gives back what it held. */
    private String notAsked(C ctx, LevelAdmission level) {
        ctx.budgetSpent = true;
        ctx.history.note(flow.name(), "tier " + level.getLevelIndex() + " (" + level.getDbName() + ") was not reserved: no time left — the ledger was not asked");
        return CallCause.ADMISSION_TIMEOUT;
    }

    private String ledgerFault(C ctx, LevelAdmission level, RuntimeException fault) {
        return ledgerFault(ctx, level.getLevelIndex(), level.getDbName(), level.getPartnerId(), fault);
    }

    private String ledgerFault(C ctx, int levelIndex, String dbName, Integer partnerId, RuntimeException fault) {
        ctx.systemFault = CallCause.BILLING_SYSTEM_ERROR;
        flow.log.error("[{}] {} | LEDGER FAULT at tier {} ({}) partner {}: {} — this is NOT a balance case", flow.name(), ctx.sessionKey,
            levelIndex, dbName, partnerId, fault.getMessage());
        return CallCause.BILLING_SYSTEM_ERROR;
    }

    /** The call switch's compensateReserves: a refused candidate gives every reserve back and frees its slot. */
    private void undo(C ctx, Walk walk, String cause) {
        ctx.levels = List.of();
        if (walk.simulated()) return;
        for (LevelAdmission level : walk.levels) release(ctx, level, cause);
        flow.slots().release(ctx.sessionKey);
        ctx.history.note(flow.name(), "candidate " + walk.tryNo + " refused: " + cause);
    }

    private void release(C ctx, LevelAdmission level, String why) {
        if (!CallSettlement.holdsReserve(level)) return;
        try {
            flow.kit().ledger().release(level, why);
        } catch (RuntimeException e) {
            flow.counters().owed.incrementAndGet();
            flow.log.error("[{}] {} | OWED: the reserve {} of {} {} at tier {} ({}) partner {} was NOT released ({}): {}", flow.name(),
                ctx.sessionKey, level.getDebitReference(), level.getTotalReserved(), level.getUom(), level.getLevelIndex(),
                level.getDbName(), level.getPartnerId(), why, e.toString());
        }
    }

    // ── a long call renews its reserve ──────────────────────────────────────

    /** A renewal runs while the call is answered, outside any admission: the ledger's own timeout bounds it. */
    private static final long RENEWAL_HAS_NO_BUDGET = Long.MAX_VALUE;

    /**
     * The default renewal of one tier (C14), in seconds: the next window as the application rates it, held through the ledger under
     * {@code …#W<n>}. The period = held, or nothing to hold (the tier does not renew, is zero-rated, never reserved); 0 = the ledger
     * refused. A ledger FAULT never cuts a call: the settlement reconciles when the call ends. The base has no "remainder": a ledger
     * that can fund part of a window is the application's own (the call switch's billing answers the seconds itself).
     */
    double renewThroughLedger(C ctx, LevelAdmission level) {
        double period = flow.kit().settings().reservePeriodSec();
        TierRate next = flow.rateNextWindow(ctx, level);
        if (next == null || !next.reserves() || level.getDebitReference() == null) return period;
        String reference = level.getDebitReference() + "#W" + (level.getReservationCount() + 1);
        String refusal = reserve(ctx, level, next.reserveAmount(), reference, CallCause.BALANCE_EXHAUSTED, RENEWAL_HAS_NO_BUDGET);
        return refusal == null || CallCause.BILLING_SYSTEM_ERROR.equals(refusal) ? period : 0;
    }
}
