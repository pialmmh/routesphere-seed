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
        int candidates = flow.safely(ctx, "candidateCount", () -> flow.candidateCount(ctx), 0);
        for (int index = 0; index < candidates; index++) {
            if (!eligible(ctx, index)) continue;
            String refusal = admitCandidate(ctx, mode);
            if (refusal == null) return accept(ctx, index);
            ctx.lastRefusal = refusal;
        }
        return AdmissionVerdict.reject(flow.safely(ctx, "rejectCause", () -> flow.rejectCause(ctx), CallCause.INTERNAL_ERROR));
    }

    private boolean eligible(C ctx, int index) {
        return flow.safely(ctx, "useCandidate", () -> flow.useCandidate(ctx, index), false);
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
        ctx.entryTenant = entry.tenant();
        ctx.partner = entry.partner();
        List<Tenant> chain = entry.tenant().getAncestorChain();
        walk.root = chain.get(chain.size() - 1);
        for (int i = 0; i < chain.size(); i++) {
            Partner partner = i == 0 ? entry.partner() : partnerAbove(ctx, chain.get(i - 1), chain.get(i));
            String refusal = admitAtLevel(ctx, walk, chain.get(i), partner, i);
            if (refusal != null) return refusal;
        }
        return null;
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

    private String rateAndReserve(C ctx, Walk walk, Tenant tier, Partner partner, int levelIndex) {
        TierRate rate = flow.isFree(ctx) ? TierRate.free() : flow.step(ctx, "RATE", () -> flow.rateAtLevel(ctx, tier, partner, levelIndex));
        if (rate == null) return CallCause.UNRATED;
        LevelAdmission level = levelOf(tier, partner, levelIndex, rate);
        String refusal = walk.simulated() ? null
            : reserve(ctx, level, rate.reserveAmount(), referenceOf(ctx, walk.tryNo, levelIndex), flow.noBalanceCause(ctx));
        if (refusal == null) walk.levels.add(level);
        return refusal;
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

    /** The ledger's reserve. Null = held (or nothing to hold). A ledger fault is never a balance cause. */
    private String reserve(C ctx, LevelAdmission level, BigDecimal amount, String reference, String causeWhenItCannotPay) {
        if (amount.signum() <= 0) return null;
        try {
            Optional<LedgerPort.Reservation> held = flow.kit().ledger().reserve(level, amount, reference);
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

    private String ledgerFault(C ctx, LevelAdmission level, RuntimeException fault) {
        ctx.systemFault = CallCause.BILLING_SYSTEM_ERROR;
        flow.log.error("[{}] {} | LEDGER FAULT at tier {} ({}) partner {}: {} — this is NOT a balance case", flow.name(), ctx.sessionKey,
            level.getLevelIndex(), level.getDbName(), level.getPartnerId(), fault.getMessage());
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

    /** Null = the call goes on. Else the cause to end it with: a tier cannot pay the next window. */
    String reserveNextWindow(C ctx) {
        for (LevelAdmission level : ctx.levels) {
            String cause = extendReserve(ctx, level);
            if (cause != null) return cause;
        }
        return null;
    }

    /** A ledger FAULT never cuts a call: the settlement reconciles when the call ends. */
    private String extendReserve(C ctx, LevelAdmission level) {
        TierRate next = flow.safely(ctx, "rateNextWindow", () -> flow.rateNextWindow(ctx, level), null);
        if (next == null || !next.reserves() || level.getDebitReference() == null) return null;
        String reference = level.getDebitReference() + "#W" + (level.getReservationCount() + 1);
        String refusal = reserve(ctx, level, next.reserveAmount(), reference, CallCause.BALANCE_EXHAUSTED);
        return CallCause.BILLING_SYSTEM_ERROR.equals(refusal) ? null : refusal;
    }
}
