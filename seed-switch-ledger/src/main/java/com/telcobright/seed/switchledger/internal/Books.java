package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.memledger.api.request.CrudRequest;
import com.telcobright.memledger.api.response.EntityResponse;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
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
 * <p>Exactly two entities are ever named: {@value #ACCOUNT} and {@value #RESERVE}. {@code packagepurchase} is never written.
 */
public final class Books {

    public static final String ACCOUNT = "PackageAccount";
    public static final String RESERVE = "PackageAccountReserve";

    private final MemLedger ledger;
    private final Clock clock;

    public Books(MemLedger ledger, Clock clock) {
        this.ledger = ledger;
        this.clock = clock;
    }

    public MemLedger ledger() { return ledger; }

    // ── reads ───────────────────────────────────────────────────────────────

    /** The account as the ledger holds it now ({@code getBalance}, 68–75; the read every verb opens with, 86–87). Null = not held. */
    public PackageAccount liveAccount(String dbName, long accountId) {
        return guarded("read account " + accountId + " of " + dbName, () -> ledger.getEntity(dbName, ACCOUNT, accountId));
    }

    /** The tier's reserve row, by its key ({@code reserveBalance} 108–109). Null = none open. */
    public PackageAccountReserve reserveRow(String dbName, String rowKey) {
        return guarded("read reserve " + rowKey + " of " + dbName, () -> ledger.getEntity(dbName, RESERVE, rowKey));
    }

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
     * The account's balance moves to {@code after}: a copy of the cached row with its metadata ({@code copyAccountMetadata} 695–702 — id,
     * purchase, name, unit), {@code balanceBefore} = the old balance, {@code balanceAfter} = the new, {@code lastAmount} = the move (negative
     * for a debit), written as a full-row UPDATE ({@code reserveBalance} 159–174, {@code returnBalance} 232–249, {@code recharge} 343–364).
     */
    public PackageAccount moveBalance(String dbName, PackageAccount live, BigDecimal after, BigDecimal lastAmount) {
        PackageAccount updated = copyOf(live);
        updated.setBalanceBefore(balanceOf(live));
        updated.setBalanceAfter(after);
        updated.setLastAmount(lastAmount);
        exec(request(dbName, ACCOUNT, "UPDATE", updated), "move the balance of account " + live.getId());
        return updated;
    }

    /** The account moves onto another purchase ({@code recharge} 347–351): the copy carries the NEW purchase, the balance stays. */
    public PackageAccount reparent(String dbName, PackageAccount live, long purchaseId) {
        PackageAccount updated = copyOf(live);
        updated.setBalanceBefore(live.getBalanceBefore());
        updated.setBalanceAfter(live.getBalanceAfter());
        updated.setLastAmount(live.getLastAmount());
        updated.setIdpackagePurchase(purchaseId);
        exec(request(dbName, ACCOUNT, "UPDATE", updated), "reparent account " + live.getId());
        return updated;
    }

    private static PackageAccount copyOf(PackageAccount source) {
        PackageAccount copy = new PackageAccount();
        copy.setId(source.getId());
        copy.setIdpackagePurchase(source.getIdpackagePurchase());
        copy.setName(source.getName());
        copy.setUom(source.getUom());
        return copy;
    }

    // ── the reserve row ─────────────────────────────────────────────────────

    /** The tier's FIRST reserve: ONE row keyed by the tier's reference ({@code reserveBalance} 133–157, the key = the event id). */
    public void openReserveRow(String dbName, String rowKey, PackageAccount live, BigDecimal amount) {
        PackageAccountReserve row = new PackageAccountReserve();
        row.setChannelCallUuid(rowKey);
        row.setIdPackageAccount(live.getId());
        row.setIdPackagePurchase(live.getIdpackagePurchase());
        row.setName("SESSION-Reserve-" + rowKey);
        row.setReserveUnit(amount);
        row.setUom(live.getUom());
        row.setTime(now());
        exec(request(dbName, RESERVE, "INSERT", row), "open the reserve row " + rowKey);
    }

    /** A later window of the same tier: the row's {@code reserveUnit} grows, its time is renewed ({@code reserveBalance} 111–131, CONSECUTIVE RESERVE). */
    public void growReserveRow(String dbName, PackageAccountReserve row, BigDecimal amount) {
        row.setReserveUnit(row.getReserveUnit().add(amount));
        row.setTime(now());
        exec(request(dbName, RESERVE, "UPDATE", row), "grow the reserve row " + row.getChannelCallUuid());
    }

    /** The row dies with the settlement or the release ({@code returnBalance} 254–255, {@code deletePackageAccountReserve} 704–713). */
    public void deleteReserveRow(String dbName, String rowKey) {
        boolean deleted = guarded("delete the reserve row " + rowKey, () -> ledger.delete(dbName, RESERVE, rowKey));
        if (!deleted) throw new LedgerFault("the reserve row " + rowKey + " of " + dbName + " was not deleted: the ledger does not hold it");
    }

    public String now() { return LocalDateTime.now(clock).toString(); }

    // ── the ledger's answers ────────────────────────────────────────────────

    private static CrudRequest request(String dbName, String entity, String operation, Object row) {
        CrudRequest request = new CrudRequest(dbName, entity, operation);
        request.setEntity(row);
        return request;
    }

    private void exec(CrudRequest request, String what) {
        EntityResponse response = guarded(what, () -> ledger.execCrud(request));
        if (response == null || !response.isSuccess()) {
            throw new LedgerFault("the ledger refused to " + what + " in " + request.getDbName() + ": " + (response == null ? "no answer" : response.getMessage()));
        }
    }

    private static <T> T guarded(String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (LedgerFault | LedgerRefusal own) {
            throw own;
        } catch (RuntimeException e) {
            throw new LedgerFault("the ledger could not " + what + ": " + e, e);
        }
    }
}
