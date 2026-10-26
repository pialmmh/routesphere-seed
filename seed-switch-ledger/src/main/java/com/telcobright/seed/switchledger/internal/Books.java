package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.seed.switchledger.api.ChargeReference;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerRefusal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The raw moves on the MemLedger — exactly the writes routesphere-core {@code PrepaidServiceWithCompensation} makes, with their lines
 * cited, and nothing else: a read of a live row, the debit or the credit of an account (a full-row UPDATE of a copy of the cached row),
 * one reserve row per tier opened, grown and deleted. No lock is taken here: the verbs ({@link MemLedgerPort}, the reaper, the credit)
 * hold the account's lock around these. Every failure of the MemLedger — it does not answer, a write is refused — is a {@link LedgerFault}.
 *
 * <p>L2b item 1 · ONE write path: every move is STAGED in a {@link UnitOfWork} the caller opened ({@link #unit()}) and written when the
 * caller commits it — ONE {@code execCrud(WALEntryBatch)}, one WAL record, however many rows and tiers the operation touches. A read
 * through the unit sees what the unit already staged. Every image staged is a NEW object: the cached row is never changed in place.
 *
 * <p>The entities named: {@value #ACCOUNT}, {@value #RESERVE} and — where registered — {@value #CHARGE} (L2b 2b).
 */
public final class Books {

    public static final String ACCOUNT = "PackageAccount";
    public static final String RESERVE = "PackageAccountReserve";
    /** L2b 2b · The durable reference of a final charge ({@link ChargeReference}), registered only where the root's ledger says so. */
    public static final String CHARGE = "PackageAccountCharge";

    private final MemLedger ledger;
    private final Clock clock;
    private final boolean keepsCharges;

    public Books(MemLedger ledger, Clock clock) { this(ledger, clock, false); }

    /** @param keepsCharges the ledger registers {@value #CHARGE}: every final charge carries its durable reference row */
    public Books(MemLedger ledger, Clock clock, boolean keepsCharges) {
        this.ledger = ledger;
        this.clock = clock;
        this.keepsCharges = keepsCharges;
    }

    public MemLedger ledger() { return ledger; }

    /** L2b 2b · Does every final charge carry its durable reference row here ({@value #CHARGE} registered)? */
    public boolean keepsCharges() { return keepsCharges; }

    /** A new unit of work over this ledger: stage the moves of ONE money operation, then commit it as ONE record. */
    public UnitOfWork unit() { return new UnitOfWork(ledger); }

    // ── reads ───────────────────────────────────────────────────────────────

    /** The account as the ledger holds it now ({@code getBalance}, 68–75; the read every verb opens with, 86–87). Null = not held. */
    public PackageAccount liveAccount(String dbName, long accountId) {
        return guarded("read account " + accountId + " of " + dbName, () -> ledger.getEntity(dbName, ACCOUNT, accountId));
    }

    /** The account as the unit will leave it (a move staged earlier in the unit), else as the ledger holds it. Null = not held. */
    public PackageAccount liveAccount(UnitOfWork unit, String dbName, long accountId) { return unit.read(dbName, ACCOUNT, accountId); }

    /** The tier's reserve row, by its key ({@code reserveBalance} 108–109), as the unit will leave it. Null = none open. */
    public PackageAccountReserve reserveRow(UnitOfWork unit, String dbName, String rowKey) { return unit.read(dbName, RESERVE, rowKey); }

    /** Every open reserve row of a database ({@code reapOrphanReserves} 535–541). */
    public Map<String, PackageAccountReserve> reserveRows(String dbName) {
        return guarded("list reserves of " + dbName, () -> ledger.getAllEntities(dbName, RESERVE));
    }

    /** Does the ledger serve this schema at all ({@code getRegisteredDatabases})? Asked only when a row is missing. */
    public boolean serves(String dbName) {
        return guarded("list the schemas", ledger::getRegisteredDatabases).contains(dbName);
    }

    public static BigDecimal balanceOf(PackageAccount account) {
        return account.getBalanceAfter() == null ? BigDecimal.ZERO : account.getBalanceAfter();
    }

    // ── the account ─────────────────────────────────────────────────────────

    /**
     * The account's balance moves to {@code after}: a copy of the row with its metadata ({@code copyAccountMetadata} 695–702 — id,
     * purchase, name, unit), {@code balanceBefore} = the old balance, {@code balanceAfter} = the new, {@code lastAmount} = the move (negative
     * for a debit), staged as a full-row UPDATE ({@code reserveBalance} 159–174, {@code returnBalance} 232–249, {@code recharge} 343–364).
     */
    public PackageAccount moveBalance(UnitOfWork unit, String dbName, PackageAccount live, BigDecimal after, BigDecimal lastAmount) {
        PackageAccount updated = copyOf(live);
        updated.setBalanceBefore(balanceOf(live));
        updated.setBalanceAfter(after);
        updated.setLastAmount(lastAmount);
        unit.update(dbName, ACCOUNT, updated.getId(), updated);
        return updated;
    }

    /**
     * L2a · A final debit — the SMS's ({@code PrepaidServiceWithCompensation.debit} 489–501): the account's balance down by {@code amount},
     * {@code lastAmount} = −amount, the same full-row UPDATE; no reserve row. The caller holds the account's lock and checked the balance.
     */
    public PackageAccount debit(UnitOfWork unit, String dbName, PackageAccount live, BigDecimal amount) {
        return moveBalance(unit, dbName, live, balanceOf(live).subtract(amount), amount.negate());
    }

    /** The account moves onto another purchase ({@code recharge} 347–351): the copy carries the NEW purchase, the balance stays. */
    public PackageAccount reparent(UnitOfWork unit, String dbName, PackageAccount live, long purchaseId) {
        PackageAccount updated = copyOf(live);
        updated.setBalanceBefore(live.getBalanceBefore());
        updated.setBalanceAfter(live.getBalanceAfter());
        updated.setLastAmount(live.getLastAmount());
        updated.setIdpackagePurchase(purchaseId);
        unit.update(dbName, ACCOUNT, updated.getId(), updated);
        return updated;
    }

    private static PackageAccount copyOf(PackageAccount source) {
        PackageAccount copy = new PackageAccount();
        copy.setId(source.getId());
        copy.setIdpackagePurchase(source.getIdpackagePurchase());
        copy.setName(source.getName());
        copy.setUom(source.getUom());
        copy.setExpireDate(source.getExpireDate());
        return copy;
    }

    // ── the reserve row ─────────────────────────────────────────────────────

    /** The tier's FIRST reserve: ONE row keyed by the tier's reference ({@code reserveBalance} 133–157, the key = the event id). */
    public void openReserveRow(UnitOfWork unit, String dbName, String rowKey, PackageAccount live, BigDecimal amount) {
        PackageAccountReserve row = new PackageAccountReserve();
        row.setChannelCallUuid(rowKey);
        row.setIdPackageAccount(live.getId());
        row.setIdPackagePurchase(live.getIdpackagePurchase());
        row.setName("SESSION-Reserve-" + rowKey);
        row.setReserveUnit(amount);
        row.setUom(live.getUom());
        row.setTime(now());
        unit.insert(dbName, RESERVE, rowKey, row);
    }

    /**
     * A later window of the same tier: a COPY of the row whose {@code reserveUnit} grows and whose time is renewed ({@code reserveBalance}
     * 111–131, CONSECUTIVE RESERVE) — the cached row is not touched before the WAL has the record.
     */
    public void growReserveRow(UnitOfWork unit, String dbName, PackageAccountReserve row, BigDecimal amount) {
        PackageAccountReserve grown = new PackageAccountReserve(row.getChannelCallUuid(), row.getIdPackageAccount(), row.getIdPackagePurchase(), row.getName(),
            (row.getReserveUnit() == null ? BigDecimal.ZERO : row.getReserveUnit()).add(amount), row.getUom(), now());
        unit.update(dbName, RESERVE, grown.getChannelCallUuid(), grown);
    }

    /**
     * A reserve row given back whole — {@code returnBalance} (211–265) as the reaper and a release use it: the row's {@code reserveUnit} is
     * credited to its account and the row dies, both in the caller's unit. The caller holds the account's lock. @return the balance after
     */
    public BigDecimal returnRow(UnitOfWork unit, String dbName, PackageAccountReserve row) {
        PackageAccount live = liveAccount(unit, dbName, row.getIdPackageAccount());
        if (live == null) throw new LedgerFault("the reserve row " + row.getChannelCallUuid() + " names account " + row.getIdPackageAccount() + ", which " + dbName + " does not hold");
        BigDecimal back = row.getReserveUnit() == null ? BigDecimal.ZERO : row.getReserveUnit();
        BigDecimal after = balanceOf(live).add(back);
        moveBalance(unit, dbName, live, after, back);
        deleteReserveRow(unit, dbName, row.getChannelCallUuid());
        return after;
    }

    // ── L2b 2b · the charge's durable reference ─────────────────────────────

    /** The charge made under {@code reference}, as the unit will leave it; null = none. */
    public ChargeReference chargeRow(UnitOfWork unit, String dbName, String reference) { return unit.read(dbName, CHARGE, reference); }

    /** The charge's reference row, in the same unit as its debit: the account, the amount, the balances it moved. */
    public void openChargeRow(UnitOfWork unit, String dbName, String reference, PackageAccount live, BigDecimal amount, BigDecimal before, BigDecimal after) {
        unit.insert(dbName, CHARGE, reference, new ChargeReference(reference, live.getId(), live.getUom(), amount, before, after, null, now()));
    }

    /** The charge's refund, in the same unit as its credit: the row says what came back (once). */
    public void markRefunded(UnitOfWork unit, String dbName, ChargeReference row, BigDecimal back) {
        unit.update(dbName, CHARGE, row.getReference(), row.refunded(back, now()));
    }

    /** The row dies with the settlement or the release ({@code returnBalance} 254–255, {@code deletePackageAccountReserve} 704–713). */
    public void deleteReserveRow(UnitOfWork unit, String dbName, String rowKey) { unit.delete(dbName, RESERVE, rowKey); }

    public String now() { return LocalDateTime.now(clock).toString(); }

    // ── the ledger's answers ────────────────────────────────────────────────

    static <T> T guarded(String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (LedgerFault | LedgerRefusal own) {
            throw own;
        } catch (RuntimeException e) {
            throw new LedgerFault("the ledger could not " + what + ": " + e, e);
        }
    }
}
