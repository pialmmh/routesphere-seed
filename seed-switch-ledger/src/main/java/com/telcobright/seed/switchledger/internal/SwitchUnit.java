package com.telcobright.seed.switchledger.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;
import com.telcobright.seed.switchledger.api.ChargeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * L2b item 2 · ONE unit of the switch ledger: the call switch's moves ({@code PrepaidServiceWithCompensation}, as {@link MemLedgerPort}
 * cites them) over EVERY tier one business operation touches, staged in ONE {@link UnitOfWork} under the accounts' locks taken in ONE global
 * order ({@link AccountLocks#holdAll}), and written by {@link #commit} as ONE WAL record — all tiers or none. Each move answers as it WILL
 * be: a later move of the same account reads the earlier one from the unit. What the port remembers (the idempotent references) is written
 * only after the record is in the WAL. {@link #close} releases the locks; a unit closed without its commit wrote nothing.
 */
final class SwitchUnit implements LedgerUnit {

    private static final Logger log = LoggerFactory.getLogger(MemLedgerPort.class);

    private final Books books;
    private final AccountLocks locks;
    private final Answers answers;
    private final UnitOfWork work;
    private final List<Runnable> afterTheRecord = new ArrayList<>();
    private AccountLocks.Held held;

    SwitchUnit(Books books, AccountLocks locks, Answers answers) {
        this.books = books;
        this.locks = locks;
        this.answers = answers;
        this.work = books.unit();
    }

    // ── the locks: once, in the global order ────────────────────────────────

    @Override
    public void lock(Collection<LevelAdmission> accounts) {
        List<AccountLocks.Key> keys = new ArrayList<>();
        for (LevelAdmission a : accounts) if (a.getPackageAccountId() != null && a.getDbName() != null) keys.add(new AccountLocks.Key(a.getDbName(), a.getPackageAccountId()));
        if (held == null) { held = locks.holdAll(keys); return; }
        for (AccountLocks.Key key : AccountLocks.inTheGlobalOrder(keys)) held.alsoHold(key);
    }

    private void holdTheLockOf(String db, long accountId) {
        if (held == null) held = locks.hold(db, accountId);
        else held.alsoHold(db, accountId);
    }

    // ── the moves ───────────────────────────────────────────────────────────

    @Override
    public Optional<BigDecimal> balanceOf(LevelAdmission level) {
        Long accountId = level.getPackageAccountId();
        if (accountId == null || level.getDbName() == null) return Optional.empty();
        PackageAccount live = books.liveAccount(work, level.getDbName(), accountId);
        return live == null ? Optional.empty() : Optional.of(Books.balanceOf(live));
    }

    /** {@code reserveBalance} 78–184: short → refused (nothing staged); else ONE row opened (133–157) or grown (107–131) and the account debited (159–174). */
    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        long accountId = accountOf(level);
        Reservation seen = answers.hold(reference);
        if (seen != null) return Optional.of(Answers.repeatOf(seen));
        String db = level.getDbName(), rowKey = rowKeyOf(level, reference);
        holdTheLockOf(db, accountId);
        PackageAccount live = heldAccount(db, accountId, level);
        BigDecimal before = Books.balanceOf(live);
        if (before.compareTo(amount) < 0) return refused(level, accountId, before, amount, reference);
        BigDecimal after = before.subtract(amount);
        PackageAccountReserve row = books.reserveRow(work, db, rowKey);
        if (row == null) books.openReserveRow(work, db, rowKey, live, amount); else books.growReserveRow(work, db, row, amount);
        books.moveBalance(work, db, live, after, amount.negate());
        Reservation held = new Reservation(live.getId(), live.getUom(), amount, before, after, false);
        afterTheRecord.add(() -> answers.rememberHold(rowKey, reference, held));
        return Optional.of(held);
    }

    /** {@code debit} 441–516: short → refused (nothing staged); zero → taken without a write (484–486); else debited (489–501), no reserve row. */
    @Override
    public Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) {
        long accountId = accountOf(level);
        Reservation seen = answers.charge(reference);
        if (seen != null) return Optional.of(Answers.repeatOf(seen));
        String db = level.getDbName();
        holdTheLockOf(db, accountId);
        Reservation durable = chargeMadeBefore(db, reference);
        if (durable != null) return Optional.of(durable);
        PackageAccount live = heldAccount(db, accountId, level);
        BigDecimal before = Books.balanceOf(live);
        if (before.compareTo(amount) < 0) return refused(level, accountId, before, amount, reference);
        BigDecimal after = amount.signum() == 0 ? before : books.debit(work, db, live, amount).getBalanceAfter();
        if (amount.signum() > 0 && books.keepsCharges()) books.openChargeRow(work, db, reference, live, amount, before, after);
        Reservation took = new Reservation(live.getId(), live.getUom(), amount, before, after, false);
        afterTheRecord.add(() -> {
            answers.rememberCharge(reference, took);
            log.info("CHARGE {} | account {} of {}: -{} {} → {}", LogText.clean(reference), accountId, db, amount, live.getUom(), after);
        });
        return Optional.of(took);
    }

    /** {@code returnBalance} 211–265 as {@code settleAgainstReserve} applies it: the reserved less the charged back, either way; the row dies. */
    @Override
    public TierSettlement settle(LevelAdmission level, BigDecimal charged) {
        String tier = level.getDebitReference();
        if (tier == null) return TierSettlement.nothing(level.getLevelIndex());
        TierSettlement done = answers.closedTier(tier);
        if (done != null) return done;
        long accountId = accountOf(level);
        String db = level.getDbName();
        holdTheLockOf(db, accountId);
        PackageAccountReserve row = books.reserveRow(work, db, tier);
        if (row == null) return nothingOpen(level, tier, charged);
        BigDecimal toReturn = TierSettlement.reservedOf(level).subtract(charged);
        noteADisagreement(level, row, tier);
        PackageAccount live = heldAccount(db, accountId, level);
        BigDecimal after = Books.balanceOf(live).add(toReturn);
        books.moveBalance(work, db, live, after, toReturn);
        books.deleteReserveRow(work, db, tier);
        TierSettlement settled = TierSettlement.of(level, charged, after);
        afterTheRecord.add(() -> answers.close(tier, settled));
        return settled;
    }

    /** The whole of the row back, the row deleted — the same {@code returnBalance}. */
    @Override
    public void release(LevelAdmission level, String why) {
        String tier = level.getDebitReference();
        if (tier == null || answers.closedTier(tier) != null) return;
        long accountId = accountOf(level);
        String db = level.getDbName();
        holdTheLockOf(db, accountId);
        PackageAccountReserve row = books.reserveRow(work, db, tier);
        if (row == null) { afterTheRecord.add(() -> answers.forget(tier)); return; }
        BigDecimal back = row.getReserveUnit() == null ? BigDecimal.ZERO : row.getReserveUnit();
        BigDecimal after = books.returnRow(work, db, row);
        TierSettlement released = new TierSettlement(level.getLevelIndex(), back, BigDecimal.ZERO, back, after, true, why);
        afterTheRecord.add(() -> answers.close(tier, released));
    }

    /** {@code BalanceCheckRule.refund} 236–255 — a recharge of the charge's given part, once per tier. */
    @Override
    public TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) {
        String tier = level.getDebitReference();
        if (tier == null) return TierSettlement.nothing(level.getLevelIndex());
        TierSettlement done = answers.closedTier(tier);
        if (done != null) return done;
        long accountId = accountOf(level);
        String db = level.getDbName();
        holdTheLockOf(db, accountId);
        ChargeReference charge = books.keepsCharges() ? books.chargeRow(work, db, tier) : null;
        if (books.keepsCharges() && amount.signum() > 0) {
            if (charge == null) throw new LedgerFault("no charge is held under " + tier + " in " + db + ": nothing to refund (a charge carries its reference row)");
            if (charge.getRefunded() != null) return refundedBefore(level, charge, why);
        }
        PackageAccount live = heldAccount(db, accountId, level);
        BigDecimal after = amount.signum() == 0 ? Books.balanceOf(live) : books.moveBalance(work, db, live, Books.balanceOf(live).add(amount), amount).getBalanceAfter();
        if (charge != null && amount.signum() > 0) books.markRefunded(work, db, charge, amount);
        BigDecimal taken = TierSettlement.reservedOf(level);
        TierSettlement refunded = new TierSettlement(level.getLevelIndex(), taken, taken.subtract(amount), amount, after, true, why);
        afterTheRecord.add(() -> {
            answers.close(tier, refunded);
            log.info("REFUND {} | account {} of {}: +{} {} → {} ({})", LogText.clean(tier), accountId, db, amount, live.getUom(), after, LogText.clean(why));
        });
        return refunded;
    }

    // ── L2b 2b · the charge's durable reference, read before anything moves ──

    /** A charge made under this reference before — this process or one before a restart: its first answer, repeated; null = none. */
    private Reservation chargeMadeBefore(String db, String reference) {
        if (!books.keepsCharges()) return null;
        ChargeReference row = books.chargeRow(work, db, reference);
        if (row == null) return null;
        Reservation first = new Reservation(row.getIdPackageAccount(), row.getUom(), row.getAmount(), row.getBalanceBefore(), row.getBalanceAfter(), false);
        afterTheRecord.add(() -> answers.rememberCharge(reference, first));
        return Answers.repeatOf(first);
    }

    /** A refund made before (its row says what came back): answered as it was, nothing moves. */
    private TierSettlement refundedBefore(LevelAdmission level, ChargeReference charge, String why) {
        TierSettlement first = new TierSettlement(level.getLevelIndex(), charge.getAmount(), charge.getAmount().subtract(charge.getRefunded()), charge.getRefunded(),
            null, true, why);
        afterTheRecord.add(() -> answers.close(level.getDebitReference(), first));
        return first;
    }

    // ── the record ──────────────────────────────────────────────────────────

    @Override
    public void commit(String what) {
        work.commit(what);
        afterTheRecord.forEach(Runnable::run);
        afterTheRecord.clear();
    }

    @Override
    public void close() {
        if (held != null) held.close();
    }

    // ── the small helpers (MemLedgerPort's, moved) ──────────────────────────

    static long accountOf(LevelAdmission level) {
        Long id = level.getPackageAccountId();
        if (id == null) {
            throw new LedgerRefusal(MemLedgerPort.NO_ACCOUNT, "tier " + level.getLevelIndex() + " (" + level.getDbName() + ") partner " + level.getPartnerId()
                + " names no package account: the rating step chose none");
        }
        return id;
    }

    /** The live row; a schema the ledger does not serve is the HOST's fault (every tier schema of the tree is registered), a missing row the tier's refusal. */
    private PackageAccount heldAccount(String db, long accountId, LevelAdmission level) {
        PackageAccount live = books.liveAccount(work, db, accountId);
        if (live != null) return live;
        if (!books.serves(db)) throw new LedgerFault("schema " + db + " is not registered in the MemLedger: the host must register every tier schema of the tree");
        throw new LedgerRefusal(MemLedgerPort.ACCOUNT_NOT_FOUND, "account " + accountId + " of tier " + level.getLevelIndex() + " is not held in schema " + db);
    }

    /** The row is the TIER's: its reference once held ({@code <sid>#L<n>}); a window ({@code …#W<n>}) grows it. */
    private static String rowKeyOf(LevelAdmission level, String reference) {
        if (level.getDebitReference() != null) return level.getDebitReference();
        int window = reference.indexOf("#W");
        return window < 0 ? reference : reference.substring(0, window);
    }

    private static Optional<Reservation> refused(LevelAdmission level, long accountId, BigDecimal balance, BigDecimal amount, String reference) {
        if (log.isDebugEnabled()) log.debug("{} | account {} of {} cannot fund {} {}: balance {}", LogText.clean(reference), accountId, level.getDbName(), amount,
            level.getUom(), balance);
        return Optional.empty();
    }

    /** The base's facts and the row's should agree; when they do not, the base's rule the money and the difference is said once. */
    private static void noteADisagreement(LevelAdmission level, PackageAccountReserve row, String tier) {
        BigDecimal held = row.getReserveUnit() == null ? BigDecimal.ZERO : row.getReserveUnit();
        if (held.compareTo(TierSettlement.reservedOf(level)) != 0) {
            log.warn("{} | the reserve row holds {} but the tier reserved {}: the tier's total settles", LogText.clean(tier), held, TierSettlement.reservedOf(level));
        }
    }

    /** No row is open under the tier: it was settled, released or reaped before. Nothing moves; the base is told it is owed, in words. */
    private static TierSettlement nothingOpen(LevelAdmission level, String tier, BigDecimal charged) {
        log.error("{} | no reserve row is open in {} for account {}: settled, released or reaped before — nothing moved", LogText.clean(tier), level.getDbName(), level.getPackageAccountId());
        return TierSettlement.owed(level, charged, "no reserve row open under " + tier + " (settled, released or reaped before): nothing moved");
    }
}
