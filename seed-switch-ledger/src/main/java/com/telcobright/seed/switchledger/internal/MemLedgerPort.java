package com.telcobright.seed.switchledger.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;
import com.telcobright.seed.switchledger.api.Credit;
import com.telcobright.seed.switchledger.api.LiveBalance;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Function;

/**
 * The base's {@link LedgerPort} over the switch's own MemLedger (ARCH-0077-A item 3) — the call switch's money, in the base's verbs,
 * exactly as routesphere-core {@code PrepaidServiceWithCompensation} moves it:
 *
 * <ul>
 *   <li><b>reserve</b> ({@code reserveBalance}, 78–184): under the account's lock, the LIVE row is read; a balance short of the amount is a
 *       refusal (empty, 94–102 — the reserve IS the affordability test, no separate balance read); else ONE reserve row keyed by the tier's
 *       reference is opened (133–157) or, for a later window of the same tier, grown (107–131) and the account is debited (159–174);</li>
 *   <li><b>settle</b> ({@code returnBalance}, 211–265, as {@code BalanceBillingService.settleAgainstReserve} applies it, 160–199): what was
 *       reserved less what the session finally costs goes back — in EITHER direction, a negative return is the extra debit of an overrun —
 *       and the row is deleted (254–255);</li>
 *   <li><b>release</b>: the whole of the row back, the row deleted — the same {@code returnBalance}.</li>
 *   <li><b>charge</b> (L2a, CHARGE_NOW: {@code debit}, 441–516): under the account's lock the LIVE row is read; short → refused (empty,
 *       nothing taken); zero → taken without a write (484–486); else the account is debited (489–501) — NO reserve row;</li>
 *   <li><b>refund</b> (L2a: the SMS's refund, {@code BalanceCheckRule.refund} 236–255 — a recharge): the given part of a charge credited
 *       back under the same lock, once per tier.</li>
 * </ul>
 *
 * <p>L2b: the moves themselves live in {@link SwitchUnit} — ONE unit of work, ONE WAL record. {@link #unit()} hands one out for a business
 * operation over every tier it touches (the engine's: every tier's hold, renewal or settlement at once); each verb here is a unit of one
 * move. Every verb is idempotent by its reference: a repeated reserve answers its first result ({@code repeated = true}) and debits
 * nothing; a repeated settle or release answers the first and moves nothing. The account is the tier's own ({@code level.getPackageAccountId()}
 * — none is the refusal {@code NO_ACCOUNT}), in the TIER's own schema. A MemLedger that does not answer is a {@link LedgerFault}, never a
 * refusal. In the process: {@link #slowestAnswerMs()} is 0.
 */
public final class MemLedgerPort implements LedgerPort, LiveBalance {

    /** The ledger refused: the tier names no account. */
    public static final String NO_ACCOUNT = "NO_ACCOUNT";
    /** The ledger refused: the account the tier names is not held in its schema. */
    public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";

    private final Books books;
    private final AccountLocks locks;
    private final Answers answers = new Answers();

    public MemLedgerPort(Books books, AccountLocks locks) {
        this.books = books;
        this.locks = locks;
    }

    /** L2b · ONE unit over every tier a business operation touches: lock all, move all, ONE record. */
    @Override public LedgerUnit unit() { return new SwitchUnit(books, locks, answers); }

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) {
        return oneMove("hold " + amount + " under " + reference, u -> u.reserve(level, amount, reference, Long.MAX_VALUE));
    }

    @Override
    public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return oneMove("settle " + level.getDebitReference(), u -> u.settle(level, charged)); }

    @Override
    public void release(LevelAdmission level, String why) { oneMove("release " + level.getDebitReference(), u -> { u.release(level, why); return null; }); }

    @Override
    public Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) {
        return oneMove("charge " + amount + " under " + reference, u -> u.charge(level, amount, reference));
    }

    @Override
    public TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) {
        return oneMove("refund " + amount + " of " + level.getDebitReference(), u -> u.refund(level, amount, why));
    }

    /** One move = one unit: its account's lock, its rows staged, ONE record (nothing when the move staged nothing). */
    private <T> T oneMove(String what, Function<SwitchUnit, T> move) {
        try (SwitchUnit unit = new SwitchUnit(books, locks, answers)) {
            T answer = move.apply(unit);
            unit.commit(what);
            return answer;
        }
    }

    // ── credit · reparent (L2a: the money roads, over the existing Credit — one lock table with the verbs above) ──

    @Override
    public Credited credit(LevelAdmission account, BigDecimal amount, String reference) {
        return creditedOf(new Credit(books, locks).credit(account.getDbName(), SwitchUnit.accountOf(account), amount, reference));
    }

    @Override
    public Credited reparent(LevelAdmission account, long purchaseId) {
        return creditedOf(new Credit(books, locks).reparent(account.getDbName(), SwitchUnit.accountOf(account), purchaseId));
    }

    private static Credited creditedOf(Credit.Credited done) { return new Credited(done.accountId(), done.before(), done.after(), done.purchaseId()); }

    // ── the peek (3b) ───────────────────────────────────────────────────────

    @Override
    public Optional<BigDecimal> balanceOf(LevelAdmission level) {
        Long accountId = level.getPackageAccountId();
        if (accountId == null || level.getDbName() == null) return Optional.empty();
        PackageAccount live = books.liveAccount(level.getDbName(), accountId);
        return live == null ? Optional.empty() : Optional.of(Books.balanceOf(live));
    }
}
