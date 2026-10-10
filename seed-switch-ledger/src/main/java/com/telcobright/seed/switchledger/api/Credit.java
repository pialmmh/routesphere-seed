package com.telcobright.seed.switchledger.api;

import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.seed.switchledger.internal.AccountLocks;
import com.telcobright.seed.switchledger.internal.Books;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

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

    /** What a credit or a reparent came to: the balance before and after, the purchase the account is on now. */
    public record Credited(long accountId, BigDecimal before, BigDecimal after, long purchaseId) {}

    private final Books books;
    private final AccountLocks locks;

    public Credit(Books books, AccountLocks locks) {
        this.books = books;
        this.locks = locks;
    }

    /**
     * Add {@code delta} (positive) to the account's live balance, under its lock; the WAL carries it to MySQL.
     *
     * @throws IllegalArgumentException a delta of zero or less (321–327), or an account the schema does not hold
     * @throws com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault the ledger did not answer, or the lock was not free in time
     */
    public Credited credit(String dbName, long accountId, BigDecimal delta, String reference) {
        if (delta == null || delta.signum() <= 0) throw new IllegalArgumentException("a credit is positive: " + delta + " (" + reference + ")");
        try (AccountLocks.Held held = locks.hold(dbName, accountId)) {
            PackageAccount live = heldAccount(dbName, accountId);
            BigDecimal before = Books.balanceOf(live);
            PackageAccount updated = books.moveBalance(dbName, live, before.add(delta), delta);
            log.info("CREDIT {} | account {} of {}: +{} {} → {}", reference, accountId, dbName, delta, live.getUom(), updated.getBalanceAfter());
            return new Credited(accountId, before, updated.getBalanceAfter(), updated.getIdpackagePurchase());
        }
    }

    /** Move the account onto purchase {@code newPurchaseId}, under its lock; the balance stays. */
    public Credited reparent(String dbName, long accountId, long newPurchaseId) {
        try (AccountLocks.Held held = locks.hold(dbName, accountId)) {
            PackageAccount live = heldAccount(dbName, accountId);
            PackageAccount updated = books.reparent(dbName, live, newPurchaseId);
            log.info("REPARENT account {} of {}: purchase {} → {}", accountId, dbName, live.getIdpackagePurchase(), newPurchaseId);
            return new Credited(accountId, Books.balanceOf(live), Books.balanceOf(updated), updated.getIdpackagePurchase());
        }
    }

    private PackageAccount heldAccount(String dbName, long accountId) {
        PackageAccount live = books.liveAccount(dbName, accountId);
        if (live == null) throw new IllegalArgumentException("account " + accountId + " is not held in schema " + dbName);
        return live;
    }
}
