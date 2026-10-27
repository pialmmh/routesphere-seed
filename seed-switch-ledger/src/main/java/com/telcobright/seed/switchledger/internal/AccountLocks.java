package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.seed.sessionflow.spi.LedgerPort.LedgerFault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The per-account lock every money verb holds — the call switch's {@code accountLocks} (routesphere-core
 * {@code PrepaidServiceWithCompensation}, line 35: {@code ConcurrentHashMap<Long, Object>}, one monitor per account id, taken by
 * {@code reserveBalance} 84–85, {@code returnBalance} 217–218, {@code recharge} 329–330, {@code debit} 474–475), here ONE LOCK PER
 * (tier, account) — made when first asked, dropped when no one holds or waits for it (bounded by the accounts in use, not by the accounts
 * there are) — and BOUNDED IN TIME (a lock not free within {@code timeoutMs} is a {@link LedgerFault}, never a hang of an admission).
 *
 * <p>L2b item 2 · A unit over several tiers takes its accounts' locks with {@link #holdAll} in ONE global order — (tier, account id) —
 * whatever order the caller names them in: two units over the same accounts in opposite tier orders never wait on each other in a circle.
 * One lock per key (no stripes) is what makes that order a LOCK order: on a striped table two different keys may share one lock, and an
 * order on the keys is then no order on the locks (mem-ledger L1's finding, its mutant {@code i1-c2}).
 *
 * <p>ONE table per MemLedger: whichever door is built first makes it, every later door over the same ledger shares it — a credit and a
 * settle on the same account never run side by side, however the doors were wired.
 *
 * <p>C1 · A lock is ONE ROW's: (tier, entity, id). An account's key is the row key of its {@code PackageAccount} — the cached ledger's
 * units ({@code LedgerCrud}) and the sessions' verbs take the very same lock for the same account. The global order is (tier, entity, id),
 * an id that is a number by its value: for the accounts of a tier it is the order of their ids, as before.
 */
public final class AccountLocks {

    private static final Map<MemLedger, AccountLocks> PER_LEDGER = Collections.synchronizedMap(new WeakHashMap<>());

    /** One row of one tier — an account, or (C1) any row a unit of the cached ledger names: the key of its lock, its place in the global order. */
    public record Key(String tier, String entity, String id) {
        static final Comparator<Key> GLOBAL_ORDER = Comparator.comparing(Key::tier).thenComparing(Key::entity).thenComparing(Key::id, AccountLocks::byValue);

        /** An account's lock: the row key of its {@code PackageAccount}. */
        public Key(String tier, long accountId) { this(tier, Books.ACCOUNT, Long.toString(accountId)); }

        /** The lock of a row of {@code entity} keyed {@code id}. */
        public static Key row(String tier, String entity, Object id) { return new Key(tier, entity, String.valueOf(id)); }

        /** The row in words: "account 11", "PackagePurchase 9". */
        String what() { return Books.ACCOUNT.equals(entity) ? "account " + id : entity + " " + id; }
    }

    /** Two ids of one entity: numbers by their value, before any id that is not a number; the rest as text. */
    private static int byValue(String a, String b) {
        Long x = numberOf(a), y = numberOf(b);
        if (x != null && y != null) return Long.compare(x, y);
        if (x != null) return -1;
        if (y != null) return 1;
        return a.compareTo(b);
    }

    private static Long numberOf(String id) {
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    private final Map<Key, Slot> slots = new ConcurrentHashMap<>();
    private final long timeoutMs;

    private AccountLocks(long timeoutMs) { this.timeoutMs = timeoutMs; }

    /** The lock table of this ledger (made on the first ask, with that ask's timeout). */
    public static AccountLocks of(MemLedger ledger, long timeoutMs) {
        return PER_LEDGER.computeIfAbsent(ledger, l -> new AccountLocks(timeoutMs));
    }

    /** Hold the account's lock; release it with {@link Held#close()}. */
    public Held hold(String dbName, long accountId) { return holdAll(List.of(new Key(dbName, accountId))); }

    /**
     * Hold every account's lock, each once, in the global order (tier, account id); a lock not free in time releases those taken and is a
     * {@link LedgerFault} naming the account.
     */
    public Held holdAll(Collection<Key> accounts) {
        Held held = new Held();
        try {
            for (Key key : inTheGlobalOrder(accounts)) held.add(key, take(key));
            return held;
        } catch (RuntimeException e) {
            held.close();
            throw e;
        }
    }

    /** The keys, once each, in the ONE order every unit takes them in. */
    static List<Key> inTheGlobalOrder(Collection<Key> accounts) {
        TreeSet<Key> sorted = new TreeSet<>(Key.GLOBAL_ORDER);
        sorted.addAll(accounts);
        return new ArrayList<>(sorted);
    }

    private Slot take(Key key) {
        Slot slot = slots.compute(key, (k, s) -> { Slot used = s == null ? new Slot() : s; used.users++; return used; });
        try {
            if (slot.lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return slot;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            leave(key);
            throw new LedgerFault("interrupted while waiting for the lock of " + key.what() + " in " + key.tier(), e);
        }
        leave(key);
        throw new LedgerFault("the lock of " + key.what() + " in " + key.tier() + " was not free within " + timeoutMs + " ms");
    }

    private void leave(Key key) { slots.compute(key, (k, s) -> s == null || --s.users <= 0 ? null : s); }

    /** The locks one verb or one unit holds, released together (newest first). */
    public final class Held implements AutoCloseable {
        private final List<Key> keys = new ArrayList<>();
        private final List<Slot> taken = new ArrayList<>();

        private Held() {}

        private void add(Key key, Slot slot) { keys.add(key); taken.add(slot); }

        /** Does this hold the account's lock already? */
        public boolean holds(String tier, long accountId) { return keys.contains(new Key(tier, accountId)); }

        /** One more account's lock, taken now (a unit that meets an account it did not name at its start: bounded by the timeout). */
        public void alsoHold(String tier, long accountId) { alsoHold(new Key(tier, accountId)); }

        /** One more row's lock, taken now — bounded by the timeout, as above. */
        public void alsoHold(Key key) {
            if (!keys.contains(key)) add(key, take(key));
        }

        @Override
        public void close() {
            for (int i = taken.size() - 1; i >= 0; i--) {
                taken.get(i).lock.unlock();
                leave(keys.get(i));
            }
            taken.clear();
            keys.clear();
        }
    }
}
