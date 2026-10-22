package com.telcobright.seed.sessionflow.samples.scene;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.samples.bed.Crash;
import com.telcobright.seed.sessionflow.spi.LedgerPort;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One node's handle on the shared ledger (P1 T10): every call passes through until the node dies; after its {@link Crash} nothing moves —
 * a dead process's call never reaches the ledger. {@link #holdSettlesUntilTheCrash()} makes the ledger slow at the worst moment: a settle
 * waits until the node dies, and then moves nothing (the process died before its settle reached the ledger).
 */
public final class CrashingLedger implements LedgerPort {

    private final LedgerPort ledger;
    private final Crash crash;
    private final AtomicInteger settlesHeld = new AtomicInteger();
    private volatile boolean holdSettles;

    public CrashingLedger(LedgerPort ledger, Crash crash) {
        this.ledger = ledger;
        this.crash = crash;
    }

    /** From now on a settle waits for the node's death (at most 10 s) and then moves nothing. */
    public CrashingLedger holdSettlesUntilTheCrash() { this.holdSettles = true; return this; }

    /** How many settles are (or were) waiting for the death. */
    public int settlesHeld() { return settlesHeld.get(); }

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) {
        if (crash.isDead()) throw new LedgerFault("the node is dead: its reserve never reached the ledger");
        return ledger.reserve(level, amount, reference);
    }

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        if (crash.isDead()) throw new LedgerFault("the node is dead: its reserve never reached the ledger");
        return ledger.reserve(level, amount, reference, withinMs);
    }

    @Override
    public TierSettlement settle(LevelAdmission level, BigDecimal charged) {
        if (holdSettles && !crash.isDead()) {
            settlesHeld.incrementAndGet();
            crash.awaitDeath(10_000);
        }
        if (crash.isDead()) return TierSettlement.nothing(level.getLevelIndex());
        return ledger.settle(level, charged);
    }

    @Override
    public void release(LevelAdmission level, String why) {
        if (!crash.isDead()) ledger.release(level, why);
    }

    @Override public long slowestAnswerMs() { return ledger.slowestAnswerMs(); }
}
