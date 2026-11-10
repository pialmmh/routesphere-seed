package com.telcobright.seed.memledgerremote.internal;

import com.telcobright.seed.memledgerremote.api.RemoteLedgerFault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The client's FIRST call at the start, waited for (the remote-fixes round, item (d)). recall-db's unit is Type=simple: "started" is not
 * "serving" — its WAL replay and its cache load come first — so right after its start the first call can miss (seen 1 of 4 cold rounds at
 * 1000 ms). The call is tried again with a short backoff (200 ms, doubled, at most 2 s; the channel's own connect backoff reset before each
 * try, so a try is a real one) until it answers or {@code start-wait-ms} passes; each failed try is said once; then the start is refused in
 * words. Only a server that is not there yet or did not answer in time is waited for ({@link RemoteLedgerFault#notServingYet()}): a
 * refused bearer, or any other answer, refuses at once.
 */
public final class StartWait {

    private static final Logger log = LoggerFactory.getLogger(StartWait.class);
    static final long FIRST_PAUSE_MS = 200, LONGEST_PAUSE_MS = 2_000;

    private StartWait() {}

    /**
     * @param address      the server, for the words
     * @param startWaitMs  how long the start waits for the first answer (0 = one try)
     * @param firstCall    the call (one try within its own deadline)
     * @param reconnectNow the channel tries to connect again at once (not after its own backoff)
     */
    public static <T> T forTheFirstAnswer(String address, long startWaitMs, Supplier<T> firstCall, Runnable reconnectNow) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startWaitMs);
        long pauseMs = FIRST_PAUSE_MS;
        for (int tryNo = 1; ; tryNo++) {
            try {
                return firstCall.get();
            } catch (RemoteLedgerFault fault) {
                if (!fault.notServingYet()) throw fault;
                long leftMs = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime());
                if (leftMs <= 0) throw notAnsweredWithin(address, startWaitMs, tryNo, fault);
                long waitMs = Math.min(pauseMs, leftMs);                         // the last try comes when the wait is over, never before
                log.warn("mem-ledger client: the start's try {} failed — {} — trying again in {} ms (start-wait-ms {})", tryNo, fault.getMessage(), waitMs, startWaitMs);
                if (!pausedFor(waitMs)) throw notAnsweredWithin(address, startWaitMs, tryNo, fault);
                reconnectNow.run();
                pauseMs = Math.min(pauseMs * 2, LONGEST_PAUSE_MS);
            }
        }
    }

    private static RemoteLedgerFault notAnsweredWithin(String address, long startWaitMs, int tries, RemoteLedgerFault last) {
        return new RemoteLedgerFault("the mem-ledger server at " + address + " did not answer the start within start-wait-ms " + startWaitMs + " (" + tries
            + (tries == 1 ? " try" : " tries") + "); the last: " + last.getMessage(), last);
    }

    /** False = interrupted: the start stops waiting. */
    private static boolean pausedFor(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
