package com.telcobright.seed.switchledger.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Optional;

/** L2b · A unit of the root's port that holds the port's gate shared until it closes ({@link OwnedMemLedgerPort#unit()}), released once. */
final class GatedUnit implements LedgerUnit {

    private final LedgerUnit unit;
    private Runnable leaveTheGate;

    GatedUnit(LedgerUnit unit, Runnable leaveTheGate) {
        this.unit = unit;
        this.leaveTheGate = leaveTheGate;
    }

    @Override public void lock(Collection<LevelAdmission> accounts) { unit.lock(accounts); }
    @Override public Optional<BigDecimal> balanceOf(LevelAdmission level) { return unit.balanceOf(level); }
    @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) { return unit.reserve(level, amount, reference, withinMs); }
    @Override public Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) { return unit.charge(level, amount, reference); }
    @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return unit.settle(level, charged); }
    @Override public void release(LevelAdmission level, String why) { unit.release(level, why); }
    @Override public TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) { return unit.refund(level, amount, why); }
    @Override public void commit(String what) { unit.commit(what); }

    @Override
    public void close() {
        try {
            unit.close();
        } finally {
            if (leaveTheGate != null) leaveTheGate.run();
            leaveTheGate = null;
        }
    }
}
