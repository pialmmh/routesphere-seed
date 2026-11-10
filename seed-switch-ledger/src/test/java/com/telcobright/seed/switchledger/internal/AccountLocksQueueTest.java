package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;
import com.telcobright.seed.switchledger.spi.LeasingLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * ARCH-0119 · the owner's rule INSIDE one application: over a ledger on the lock road an account has a SHORT queue of waiting units; one
 * more is answered "busy" at once — it never joins a longer queue and never waits out the lock's timeout — and a lock not free in time is
 * busy too. Over any other ledger the queue has no cap and a timeout is the fault it always was.
 */
class AccountLocksQueueTest {

    private static final AccountLocks.Key ACCOUNT = new AccountLocks.Key("btcl", 11);
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void stop() { pool.shutdownNow(); }

    @Test
    void onTheLockRoad_aFullQueueAnswersBusyAtOnce_andTheWaitersStillGetTheirTurn() throws Exception {
        AccountLocks locks = AccountLocks.of(ledgerOnTheLockRoad(2), 5_000);
        AccountLocks.Held holder = locks.hold("btcl", 11);
        List<Future<Boolean>> waiters = List.of(pool.submit(() -> holdAndGiveBack(locks)), pool.submit(() -> holdAndGiveBack(locks)));
        awaitWaiters(locks, 2);

        long startedAt = System.nanoTime();
        Throwable refused = pool.submit(() -> catchThrowable(() -> locks.hold("btcl", 11))).get(2, TimeUnit.SECONDS);
        long tookMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(refused).as("the third unit to wait: no place").isInstanceOf(LedgerFault.class).hasMessageContaining("the queue of account 11 in btcl is full (2 waiting");
        assertThat(((LedgerFault) refused).busy()).isTrue();
        assertThat(tookMs).as("answered at once (" + tookMs + " ms), not after the lock's 5000 ms").isLessThan(1_000);

        holder.close();
        for (Future<Boolean> waiter : waiters) assertThat(waiter.get(5, TimeUnit.SECONDS)).as("a unit that had its place gets the account").isTrue();
        try (AccountLocks.Held after = locks.hold("btcl", 11)) {
            assertThat(after.holds("btcl", 11)).as("the queue empties: a new unit is taken").isTrue();
        }
    }

    @Test
    void onTheLockRoad_theHoldersOwnSecondHold_isNeverRefusedByAFullQueue() throws Exception {
        AccountLocks locks = AccountLocks.of(ledgerOnTheLockRoad(1), 5_000);
        try (AccountLocks.Held holder = locks.hold("btcl", 11)) {
            Future<Boolean> waiter = pool.submit(() -> holdAndGiveBack(locks));
            awaitWaiters(locks, 1);

            try (AccountLocks.Held again = locks.hold("btcl", 11)) {
                assertThat(again.holds("btcl", 11)).as("the holder's own thread: its second hold never waits, so it needs no place").isTrue();
            }
            holder.close();
            assertThat(waiter.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void onTheLockRoad_aLockNotFreeInTime_isBusyToo() throws Exception {
        AccountLocks locks = AccountLocks.of(ledgerOnTheLockRoad(32), 200);
        try (AccountLocks.Held holder = locks.hold("btcl", 11)) {
            Throwable late = pool.submit(() -> catchThrowable(() -> locks.hold("btcl", 11))).get(5, TimeUnit.SECONDS);

            assertThat(late).isInstanceOf(LedgerFault.class).hasMessageContaining("was not free within 200 ms");
            assertThat(((LedgerFault) late).busy()).as("nothing was written and nothing is broken: busy").isTrue();
        }
    }

    @Test
    void withoutTheLockRoad_theQueueHasNoCap_andATimeoutIsTheFaultItAlwaysWas() throws Exception {
        AccountLocks locks = AccountLocks.of(aLedger(false, 0), 400);
        try (AccountLocks.Held holder = locks.hold("btcl", 11)) {
            List<Future<Throwable>> waiters = new ArrayList<>();
            for (int i = 0; i < 6; i++) waiters.add(pool.submit(() -> catchThrowable(() -> locks.hold("btcl", 11))));

            for (Future<Throwable> waiter : waiters) {
                Throwable fault = waiter.get(5, TimeUnit.SECONDS);
                assertThat(fault).as("every one of the six WAITED (no cap) and then timed out").isInstanceOf(LedgerFault.class).hasMessageContaining("was not free within 400 ms");
                assertThat(((LedgerFault) fault).busy()).as("the fault it always was").isFalse();
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static boolean holdAndGiveBack(AccountLocks locks) {
        try (AccountLocks.Held mine = locks.hold("btcl", 11)) {
            return mine.holds("btcl", 11);
        }
    }

    /** Wait until {@code n} units are queued behind the holder: a third arrival is judged against a queue that is really there. */
    private static void awaitWaiters(AccountLocks locks, int n) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (locks.waitingFor(ACCOUNT) < n) {
            if (System.nanoTime() > until) throw new IllegalStateException("only " + locks.waitingFor(ACCOUNT) + " of " + n + " waiter(s) queued within 5 s");
            Thread.sleep(5);
        }
    }

    private static MemLedger ledgerOnTheLockRoad(int queueMax) { return aLedger(true, queueMax); }

    /** A ledger the lock table is made for: only what {@link AccountLocks#of} asks is answered. A new object each time = a lock table of its own. */
    private static MemLedger aLedger(boolean lockRoad, int queueMax) {
        Class<?>[] kinds = lockRoad ? new Class<?>[] {MemLedger.class, LeasingLedger.class} : new Class<?>[] {MemLedger.class};
        Object identity = new Object();
        return (MemLedger) Proxy.newProxyInstance(MemLedger.class.getClassLoader(), kinds, (proxy, method, args) -> switch (method.getName()) {
            case "holdsRows" -> true;
            case "waitersOneRowMayHave" -> queueMax;
            case "hashCode" -> System.identityHashCode(identity);
            case "equals" -> proxy == args[0];
            case "toString" -> "a ledger " + (lockRoad ? "on the lock road, queue " + queueMax : "with no lock road");
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
}
