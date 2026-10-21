package com.telcobright.seed.sessionflow.samples.bed;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One node's death, shared by every handle the node holds on the world outside its process — its store ({@link CrashingStore}), its
 * ledger and its CDR road (the scene's {@code CrashingLedger}, {@code CrashingCdrSink}). Before {@link #now()} every call passes; after it
 * nothing the node does reaches the world, exactly like a dead process. A call the dying process was making when it died can be held
 * until the death ({@link #awaitDeath}) — the slow ledger the process never got an answer from.
 */
public final class Crash {

    private final AtomicBoolean dead = new AtomicBoolean();
    private final CountDownLatch died = new CountDownLatch(1);

    /** The node dies now. */
    public void now() { if (dead.compareAndSet(false, true)) died.countDown(); }

    public boolean isDead() { return dead.get(); }

    /** Wait until the node died (at most {@code millis}): true = it did. */
    public boolean awaitDeath(long millis) {
        try {
            return died.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return dead.get();
        }
    }
}
