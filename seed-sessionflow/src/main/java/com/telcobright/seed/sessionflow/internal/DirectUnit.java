package com.telcobright.seed.sessionflow.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LedgerPort.Reservation;
import com.telcobright.seed.sessionflow.spi.LedgerUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * L2b item 2 · The unit of a ledger that cannot write a batch (orchestrix's roads, a lane's own port, the testkit's): each move applied AT
 * ONCE through the port's own verb; {@link #commit} has nothing left to write; a unit closed without its commit gives back what it took,
 * newest first — the holds released, the charges refunded (the call switch's {@code compensateReserves}: the saga, kept only where no batch
 * exists). A give-back the ledger refuses is said once (the reaper's net stays).
 */
public final class DirectUnit implements LedgerUnit {

    private static final Logger log = LoggerFactory.getLogger(DirectUnit.class);

    private record Taken(LevelAdmission level, String reference, BigDecimal amount, boolean charge) {}

    private final LedgerPort port;
    private final List<Taken> taken = new ArrayList<>();
    private boolean committed;

    public DirectUnit(LedgerPort port) { this.port = port; }

    @Override public void lock(Collection<LevelAdmission> accounts) { }

    @Override public Optional<BigDecimal> balanceOf(LevelAdmission level) { return port.balanceOf(level); }

    @Override
    public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference, long withinMs) {
        Optional<Reservation> held = port.reserve(level, amount, reference, withinMs);
        held.filter(h -> !h.repeated()).ifPresent(h -> taken.add(new Taken(level, reference, h.reserved(), false)));
        return held;
    }

    @Override
    public Optional<Reservation> charge(LevelAdmission level, BigDecimal amount, String reference) {
        Optional<Reservation> took = port.charge(level, amount, reference);
        took.filter(h -> !h.repeated()).ifPresent(h -> taken.add(new Taken(level, reference, h.reserved(), true)));
        return took;
    }

    @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return port.settle(level, charged); }

    @Override public void release(LevelAdmission level, String why) { port.release(level, why); }

    @Override public TierSettlement refund(LevelAdmission level, BigDecimal amount, String why) { return port.refund(level, amount, why); }

    @Override public void commit(String what) { committed = true; }

    /** Not committed: every hold and charge taken here goes back, newest first. */
    @Override
    public void close() {
        if (committed) return;
        committed = true;
        for (int i = taken.size() - 1; i >= 0; i--) giveBack(taken.get(i));
    }

    private void giveBack(Taken t) {
        if (t.level().getDebitReference() == null) t.level().setDebitReference(tierOf(t.reference()));
        if (t.level().getTotalReserved() == null || t.level().getTotalReserved().signum() == 0) t.level().setReservedAmount(t.amount());
        try {
            if (t.charge()) port.refund(t.level(), t.amount(), "the unit was not committed");
            else port.release(t.level(), "the unit was not committed");
        } catch (RuntimeException e) {
            log.error("{} | the {} of {} at tier {} ({}) was NOT given back when its unit was not committed: {}", LogText.clean(t.reference()),
                t.charge() ? "charge" : "hold", t.amount(), t.level().getLevelIndex(), t.level().getDbName(), LogText.failureText(e));
        }
    }

    /** A window's reference ({@code <tier>#W<n>}) belongs to its tier's. */
    private static String tierOf(String reference) {
        int window = reference.indexOf("#W");
        return window < 0 ? reference : reference.substring(0, window);
    }
}
