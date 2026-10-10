package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The per-account lock every money verb holds — the call switch's {@code accountLocks} (routesphere-core
 * {@code PrepaidServiceWithCompensation}, line 35: {@code ConcurrentHashMap<Long, Object>}, one monitor per account id, taken by
 * {@code reserveBalance} 84–85, {@code returnBalance} 217–218, {@code recharge} 329–330, {@code debit} 474–475), here STRIPED (a fixed table of
 * locks by the account's hash, bounded whatever the number of accounts) and BOUNDED IN TIME (a lock not free within {@code timeoutMs} is a
 * {@link LedgerFault}, never a hang of a session's admission).
 *
 * <p>ONE table per MemLedger: whichever door is built first makes it, every later door over the same ledger shares it — a credit and a
 * settle on the same account never run side by side, however the doors were wired.
 */
public final class AccountLocks {

    private static final int STRIPES = 1024;
    private static final Map<MemLedger, AccountLocks> PER_LEDGER = Collections.synchronizedMap(new WeakHashMap<>());

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
    private final long timeoutMs;

    private AccountLocks(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        for (int i = 0; i < STRIPES; i++) stripes[i] = new ReentrantLock();
    }

    /** The lock table of this ledger (made on the first ask, with that ask's timeout). */
    public static AccountLocks of(MemLedger ledger, long timeoutMs) {
        return PER_LEDGER.computeIfAbsent(ledger, l -> new AccountLocks(timeoutMs));
    }

    /** Hold the account's lock; release it with {@link Held#close()}. */
    public Held hold(String dbName, long accountId) {
        ReentrantLock lock = stripes[Math.floorMod((dbName + "#" + accountId).hashCode(), STRIPES)];
        try {
            if (lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return new Held(lock);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LedgerFault("interrupted while waiting for the lock of account " + accountId + " in " + dbName, e);
        }
        throw new LedgerFault("the lock of account " + accountId + " in " + dbName + " was not free within " + timeoutMs + " ms");
    }

    /** One held lock. */
    public static final class Held implements AutoCloseable {
        private final ReentrantLock lock;
        private Held(ReentrantLock lock) { this.lock = lock; }
        @Override public void close() { lock.unlock(); }
    }
}
