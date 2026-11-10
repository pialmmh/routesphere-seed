package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An embedded MemLedger's WAL consumer — the one writer of its bookmark row (the remote-fixes round's closing, item (b)). mem-ledger
 * f739824's {@code shutdown()} tells it to stop, waits 5 s for it, and returns all the same ({@code MemLedgerImpl.java:1092-1107}): a
 * consumer inside a slow batch — up to 10,000 entries in ONE transaction that also moves the bookmark row — outlives it. mem-ledger names no
 * API for it, so the thread is read from its private field {@code consumerThread} (the current one: the supervisor's restart replaces it,
 * {@code :489}) — pinned to f739824; a MemLedger without that field is "none seen", said once.
 */
final class LedgerConsumer {

    private static final Logger log = LoggerFactory.getLogger(LedgerConsumer.class);
    private static final String FIELD = "consumerThread";
    private static final AtomicBoolean SAID_UNSEEN = new AtomicBoolean();

    private LedgerConsumer() {}

    /** The ledger's WAL consumer while it still runs; empty = ended, never started, or not seen (another mem-ledger build). */
    static Optional<Thread> stillRunning(MemLedger ledger) {
        return threadOf(ledger).filter(Thread::isAlive);
    }

    private static Optional<Thread> threadOf(MemLedger ledger) {
        try {
            Field field = ledger.getClass().getDeclaredField(FIELD);
            field.setAccessible(true);
            return Optional.ofNullable((Thread) field.get(ledger));
        } catch (ReflectiveOperationException | RuntimeException unseen) {
            if (SAID_UNSEEN.compareAndSet(false, true)) {
                log.warn("the WAL consumer of {} cannot be seen ({}): what the ledger held is given back when its shutdown returns", ledger.getClass().getName(), unseen.toString());
            }
            return Optional.empty();
        }
    }
}
