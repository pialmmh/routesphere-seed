package com.telcobright.seed.switchledger.api;

import com.telcobright.seed.sessionflow.api.CachedLedger;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.switchledger.internal.AccountLocks;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.LedgerCrud;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * The ONE credit primitive of the switch ledger (ARCH-0077-A item 5) — routesphere-core {@code PrepaidServiceWithCompensation.recharge},
 * lines 282–382: "MemLedger is the source of truth and persists to MySQL write-behind via the WAL — so a recharge written DIRECTLY to MySQL
 * is invisible to billing and gets overwritten by the next write-behind … Any credit written straight to MySQL is destroyed within minutes
 * on an account with call traffic (observed live on ccl98 partner 222: two portal payments wiped by the next hangup). External writers (the
 * billing portal) must therefore call this, exposed as {@code POST /api/v1/billing/recharge}." Takes a DELTA, not an absolute balance, and
 * holds the SAME per-account lock the reserve and the settle take (329–330), so it is race-free against a session settling concurrently.
 *
 * <p>{@link #reparent} moves the account onto a new purchase through the ledger (298–316, 347–351: "a parent link written straight to MySQL
 * is reverted by the next call settle … since the account's expiry is derived from its parent purchase, moving the link is what makes a
 * topup's longer validity real"). Behind a switch's recharge road; the WiFi does not call it at go-live.
 */
public final class Credit {

    private static final Logger log = LoggerFactory.getLogger(Credit.class);
    private static final String BALANCE = "balanceAfter";
    private static final String PURCHASE = "idpackagePurchase";

    /** What a credit or a reparent came to: the balance before and after, the purchase the account is on now. */
    public record Credited(long accountId, BigDecimal before, BigDecimal after, long purchaseId) {}

    private final LedgerCrud ledger;

    public Credit(Books books, AccountLocks locks) { this(new LedgerCrud(books, locks)); }

    /** C1 · The credit over a cached ledger — the port's own: one memory, and its partner → accounts index told when an account moves. */
    public Credit(LedgerCrud ledger) { this.ledger = ledger; }

    /**
     * Add {@code delta} (positive) to the account's live balance, under its lock; the WAL carries it to MySQL. C1: ONE unit of the cached
     * ledger — an {@code add} of the account's balance (before = old, after = old + delta, last = delta), the lock the sessions take.
     *
     * @throws IllegalArgumentException a delta of zero or less (321–327), or an account the schema does not hold
     * @throws com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault the ledger did not answer, or the lock was not free in time
     */
    public Credited credit(String dbName, long accountId, BigDecimal delta, String reference) {
        if (delta == null || delta.signum() <= 0) throw new IllegalArgumentException("a credit is positive: " + delta + " (" + reference + ")");
        heldAccount(dbName, accountId);
        PackageAccount after = written(ledger.unit().add(dbName, PackageAccount.class, accountId, BALANCE, delta));
        log.info("CREDIT {} | account {} of {}: +{} {} → {}", LogText.clean(reference), accountId, dbName, delta, after.getUom(), after.getBalanceAfter());
        return new Credited(accountId, after.getBalanceBefore(), after.getBalanceAfter(), after.getIdpackagePurchase());
    }

    /** Move the account onto purchase {@code newPurchaseId}, under its lock; the balance stays. C1: ONE unit — an update of its purchase. */
    public Credited reparent(String dbName, long accountId, long newPurchaseId) {
        PackageAccount before = heldAccount(dbName, accountId);
        PackageAccount after = written(ledger.unit().update(dbName, PackageAccount.class, accountId, Map.of(PURCHASE, newPurchaseId)));
        log.info("REPARENT account {} of {}: purchase {} → {}", accountId, dbName, before.getIdpackagePurchase(), newPurchaseId);
        return new Credited(accountId, Books.balanceOf(after), Books.balanceOf(after), after.getIdpackagePurchase());
    }

    /** The account as the ledger holds it now — the same words as before for an account (or a schema) it does not hold. */
    private PackageAccount heldAccount(String dbName, long accountId) {
        Optional<PackageAccount> live = ledger.servesTier(dbName) ? ledger.get(dbName, PackageAccount.class, accountId) : Optional.empty();
        return live.orElseThrow(() -> new IllegalArgumentException("account " + accountId + " is not held in schema " + dbName));
    }

    /** The account as the unit wrote it; a refusal (the account went meanwhile) in its words. */
    private static PackageAccount written(CachedLedger.Unit unit) {
        CachedLedger.Outcome outcome = unit.commit();
        if (outcome instanceof CachedLedger.Refused refused) throw new IllegalArgumentException(refused.reason());
        return (PackageAccount) ((CachedLedger.Committed) outcome).rows().get(0);
    }
}
