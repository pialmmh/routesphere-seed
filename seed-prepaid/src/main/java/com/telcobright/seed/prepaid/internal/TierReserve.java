package com.telcobright.seed.prepaid.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.api.TierRate;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import com.telcobright.statewalk.session.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * (e) The hold of ONE tier, for every application — MOVED as it was from the base's {@code AdmissionChain} (L2a 2a, design §11: the base
 * loses every money step of its own): the tier rated by the lane and held through the kit's one ledger port at admission, under the
 * session's and the tier's reference; a refused candidate's tiers given back; the journal of the sessions in the air told of each; a long
 * session's next window held ({@code …#W<n>}), the account rotated when the lane names the next one. The lane's answers are read through
 * {@link LaneAnswers} (the flow's own hooks). Stateless: one per ask.
 */
final class TierReserve<C extends RoutesphereContext> {

    /** The base's own log name: the lines read exactly as before the move. */
    private static final Logger log = LoggerFactory.getLogger(RoutesphereFlow.class);

    private final LaneAnswers<C> flow;

    private TierReserve(LaneAnswers<C> flow) { this.flow = flow; }

    static <C extends RoutesphereContext> TierReserve<C> of(LaneAnswers<C> flow) { return new TierReserve<>(flow); }

    // ── (e) the reserve ─────────────────────────────────────────────────────

    /**
     * The default of {@code reserveBalance}: the tier is rated by the application and reserved by the base — or, when the application's own
     * step did both in one move ({@link TierRate#held}), taken as that step left it. A rating hook may refuse in the ledger's own words.
     */
    Verdict rateAndReserve(C ctx, Tier tier, Partner partner) {
        TierRate rate;
        try {
            rate = flow.isFree(ctx, ctx.candidateBeingAdmitted()) ? TierRate.free() : flow.step(ctx, "RATE", () -> flow.rateAtLevel(ctx, tier, partner));
        } catch (LedgerPort.LedgerRefusal refused) {
            return Verdict.reject(refused.code());
        } catch (LedgerPort.LedgerFault fault) {
            return Verdict.reject(ledgerFault(ctx, tier.levelIndex(), tier.dbName(), partner.getIdPartner(), fault));
        }
        if (rate == null) return Verdict.reject(SessionCause.UNRATED);
        if (rate.held() != null) return takeHeld(ctx, rate.held(), tier.levelIndex());
        LevelAdmission level = levelOf(tier.tenant(), partner, tier.levelIndex(), rate);
        if (ctx.dryRun) { ctx.walked(level); return Verdict.ok(); }
        String reference = referenceOf(ctx, tier.levelIndex()), cause = flow.noBalanceCause(ctx);
        WalkPlan.of(ctx).add(List.of(level), () -> holdAndWalk(ctx, level, rate.reserveAmount(), reference, cause));
        return Verdict.ok();
    }

    /** L2b · The planned tier held in the walk's unit (every tier at once): held → walked once the unit's record is written. */
    private String holdAndWalk(C ctx, LevelAdmission level, BigDecimal amount, String reference, String cause) {
        String refusal = reserve(ctx, level, amount, reference, cause, flow.admissionTimeLeftMs(ctx));
        if (refusal == null) MoneyUnit.afterTheRecord(() -> ctx.walked(level));
        return refusal;
    }

    /** The application's own step admitted the tier in one move: the base takes its level, names its reference, asks its ledger nothing. */
    private Verdict takeHeld(C ctx, LevelAdmission held, int levelIndex) {
        if (held.getDebitReference() == null) held.setDebitReference(referenceOf(ctx, levelIndex));
        ctx.walked(held);
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
    static String referenceOf(RoutesphereContext ctx, int levelIndex) { return referenceOf(ctx.sessionId(), ctx.candidatesTried, levelIndex); }

    /** 2b · The same rule on a lane's own reference (a Prepaid's {@code reference(ctx -> id)}): {@code <id>[#<try>]#L<tier>}. */
    static String referenceOf(String id, int candidatesTried, int levelIndex) {
        int tryNo = Math.max(1, candidatesTried);
        return (tryNo <= 1 ? id : id + "#" + tryNo) + "#L" + levelIndex;
    }

    /** The ledger's reserve, inside what is left of the budget. Null = held (or nothing to hold). A ledger fault is never a balance cause. */
    private String reserve(C ctx, LevelAdmission level, BigDecimal amount, String reference, String causeWhenItCannotPay, long withinMs) {
        if (amount.signum() <= 0) return null;
        if (withinMs <= 0) return notAsked(ctx, level);
        try {
            Optional<LedgerPort.Reservation> held = MoneyUnit.moves(flow.kit().ledger()).reserve(level, amount, reference, withinMs);
            if (held.isEmpty()) return causeWhenItCannotPay;
            MoneyUnit.afterTheRecord(() -> {
                recordReserve(level, held.get(), reference);
                journalReserve(ctx, level, held.get().reserved());
            });
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

    String ledgerFault(C ctx, int levelIndex, String dbName, Integer partnerId, RuntimeException fault) {
        ctx.systemFault = SessionCause.BILLING_SYSTEM_ERROR;
        log.error("[{}] {} | LEDGER FAULT at tier {} ({}) partner {}: {} — this is NOT a balance case", flow.name(), LogText.clean(ctx.sessionId()),
            levelIndex, dbName, partnerId, LogText.failureText(fault.getMessage()));
        return SessionCause.BILLING_SYSTEM_ERROR;
    }

    // ── 2b · a lane with a Prepaid of its own: the tier held on the first account that pays ──

    /** What a walk of a tier's accounts came to: the verdict and, when held, the tier's level. */
    record Held(Verdict verdict, LevelAdmission level) {}

    /** The moved reserve's "this account cannot fund it" — never a session's cause: the walk tries the next account. */
    private static final String CANNOT_PAY = "\u0000CANNOT_PAY";

    /**
     * 2b · P2 (routesphere-core {@code ReserveBalanceStep} 82–123): ONE tier held on the FIRST of {@code accounts} that pays, in their
     * order — each rated by {@code rating} and held through the moved reserve (above, unchanged) under the tier's ONE reference; an account
     * that cannot fund it is skipped ("an exhausted package falls through to the customer's cash"); a rating's refusal, a fault, a ledger
     * refusal of its own or no time left ends the walk with its cause; none pays → {@code causeWhenNone}. A dry run rates the first and
     * holds nothing. {@code chargeNow} (item 4, CHARGE_NOW): the account is CHARGED at once instead of held — no hold row.
     */
    Held holdOnTheFirstThatPays(C ctx, Tier tier, Partner partner, List<PackageAccount> accounts, Function<PackageAccount, TierRate> rating,
                                String reference, Supplier<String> causeWhenNone, boolean chargeNow) {
        for (PackageAccount account : accounts) {
            Held held = holdOn(ctx, tier, partner, rating, account, reference, chargeNow);
            if (held != null) return held;
        }
        return new Held(Verdict.reject(causeWhenNone.get()), null);
    }

    /** One account: held (or, in a dry run, rated) → the tier's level; a cause that ends the walk; null = it cannot fund it, try the next. */
    private Held holdOn(C ctx, Tier tier, Partner partner, Function<PackageAccount, TierRate> rating, PackageAccount account, String reference,
                        boolean chargeNow) {
        TierRate rate;
        try {
            rate = rating.apply(account);
        } catch (LedgerPort.LedgerRefusal refused) {
            return new Held(Verdict.reject(refused.code()), null);
        } catch (LedgerPort.LedgerFault fault) {
            return new Held(Verdict.reject(ledgerFault(ctx, tier.levelIndex(), tier.dbName(), partner.getIdPartner(), fault)), null);
        }
        if (rate == null) return new Held(Verdict.reject(SessionCause.UNRATED), null);
        LevelAdmission level = levelOf(tier.tenant(), partner, tier.levelIndex(), rate);
        String refusal = ctx.dryRun ? null : chargeNow ? charge(ctx, level, rate.reserveAmount(), reference, flow.admissionTimeLeftMs(ctx))
            : reserve(ctx, level, rate.reserveAmount(), reference, CANNOT_PAY, flow.admissionTimeLeftMs(ctx));
        if (CANNOT_PAY.equals(refusal)) return null;
        if (refusal != null) return new Held(Verdict.reject(refusal), null);
        MoneyUnit.afterTheRecord(() -> ctx.walked(level));
        return new Held(Verdict.ok(), level);
    }

    /**
     * 4 · CHARGE_NOW: the tier's account charged at once through the ledger port — the SMS's final debit, no hold row
     * ({@code PrepaidServiceWithCompensation.debit} 441–516) — and recorded on the level exactly as a hold is (its reference, account, the
     * amount taken, the balances). Null = taken (or nothing to take); CANNOT_PAY = short; else the cause. Not journaled: a charge is final.
     */
    private String charge(C ctx, LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        if (amount.signum() <= 0) return null;
        if (withinMs <= 0) return notAsked(ctx, level);
        try {
            Optional<LedgerPort.Reservation> taken = MoneyUnit.moves(flow.kit().ledger()).charge(level, amount, reference);
            if (taken.isEmpty()) return CANNOT_PAY;
            MoneyUnit.afterTheRecord(() -> recordReserve(level, taken.get(), reference));
            return null;
        } catch (LedgerPort.LedgerRefusal refused) {
            return refused.code();
        } catch (LedgerPort.LedgerFault fault) {
            return ledgerFault(ctx, level.getLevelIndex(), level.getDbName(), level.getPartnerId(), fault);
        }
    }

    /** 4 · A refused candidate's CHARGED tier: the whole charge given back; a refund the ledger refuses is OWED (as a release is). */
    void refundCharge(C ctx, LevelAdmission level, String why) {
        if (!SessionSettlement.holdsReserve(level)) return;
        try {
            MoneyUnit.moves(flow.kit().ledger()).refund(level, TierSettlement.reservedOf(level), why);
        } catch (RuntimeException e) {
            flow.kit().counters().owed.incrementAndGet();
            log.error("[{}] {} | OWED: the charge {} of {} {} at tier {} ({}) partner {} was NOT given back ({}): {}", flow.name(),
                LogText.clean(ctx.sessionId()), LogText.clean(level.getDebitReference()), level.getTotalReserved(), level.getUom(), level.getLevelIndex(),
                level.getDbName(), level.getPartnerId(), LogText.clean(why), LogText.failureText(e));
        }
    }

    // ── giving it back ──────────────────────────────────────────────────────

    /** One tier of a refused candidate (the call switch's compensateReserves): its reserve back; a release the ledger refuses is OWED. */
    void release(C ctx, LevelAdmission level, String why) {
        if (!SessionSettlement.holdsReserve(level)) return;
        try {
            MoneyUnit.moves(flow.kit().ledger()).release(level, why);
            MoneyUnit.afterTheRecord(() -> journalRelease(ctx, level));
        } catch (RuntimeException e) {
            flow.kit().counters().owed.incrementAndGet();
            log.error("[{}] {} | OWED: the reserve {} of {} {} at tier {} ({}) partner {} was NOT released ({}): {}", flow.name(),
                LogText.clean(ctx.sessionId()), LogText.clean(level.getDebitReference()), level.getTotalReserved(), level.getUom(), level.getLevelIndex(),
                level.getDbName(), level.getPartnerId(), LogText.clean(why), LogText.failureText(e));
        }
    }

    // ── F9 · the journal of the sessions in the air takes every reserve as it is held ──────────

    private void journalReserve(C ctx, LevelAdmission level, BigDecimal amount) {
        SessionJournal journal = flow.kit().journal();
        if (!journal.keeps()) return;
        try {
            SessionJournal.Held held = new SessionJournal.Held(level.getLevelIndex(), flow.rootDbOf(ctx), level.getDbName(), level.getPartnerId(),
                level.getPackageAccountId(), level.getUom(), amount, level.getDebitReference());
            journal.reserved(ctx.sessionId(), flow.kit().clock().millis(), flow.lostRecordsOf(ctx), held);
        } catch (RuntimeException e) {
            long refused = flow.kit().counters().journalRefused.incrementAndGet();
            ctx.history.note(flow.name(), "the reserve " + level.getDebitReference() + " could not be written to the journal of the sessions in the air ("
                + journal.where() + "): " + e.getMessage());
            if (worthSaying(refused)) log.warn("[{}] {} | the reserve {} was NOT written to the journal of the sessions in the air ({}): a restart "
                    + "would not know it — {} journal refusals so far (said at 1, 10, 100, …): {}", flow.name(), LogText.clean(ctx.sessionId()),
                LogText.clean(level.getDebitReference()), journal.where(), refused, LogText.failureText(e));
        }
    }

    /** Flood-safe: a journal that refuses every reserve is said at the 1st, 10th, 100th … refusal, never once per session. */
    static boolean worthSaying(long count) {
        if (count < 1) return false;
        while (count % 10 == 0) count /= 10;
        return count == 1;
    }

    void journalRelease(C ctx, LevelAdmission level) {
        SessionJournal journal = flow.kit().journal();
        if (!journal.keeps() || level.getDebitReference() == null) return;
        try {
            journal.released(ctx.sessionId(), level.getDebitReference());
        } catch (RuntimeException e) {
            log.warn("[{}] {} | the journal did not take the release of {} (the ledger moves money once per reference): {}",
                flow.name(), LogText.clean(ctx.sessionId()), LogText.clean(level.getDebitReference()), LogText.failureText(e));
        }
    }

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
