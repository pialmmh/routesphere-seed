package com.telcobright.seed.switchledger.dependencies;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.api.SwitchLedger;
import com.telcobright.seed.switchledger.internal.AccountLocks;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.seed.switchledger.internal.OrphanReaper;

import java.time.Clock;
import java.util.Objects;

/** The switch ledger a host builds over the MemLedger it already runs (ARCH-0077-A). */
public final class SwitchLedgers {

    private SwitchLedgers() {}

    /** Every door of the switch ledger over this MemLedger, on one per-account lock table. */
    public static SwitchLedger over(MemLedger memLedger, SwitchLedgerSettings settings, Clock clock) {
        Objects.requireNonNull(memLedger, "memLedger");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clock, "clock");
        Books books = new Books(memLedger, clock);
        AccountLocks locks = AccountLocks.of(memLedger, settings.lockTimeoutMs());
        MemLedgerPort port = new MemLedgerPort(books, locks);
        return new SwitchLedger(port, port, new OrphanReaper(books, locks, settings, clock));
    }

    /** The orphan reaper alone, for the host's schedule (the base's slot-reconcile cadence): {@code reap(dbName)} per tier schema. */
    public static OrphanReaper reaper(MemLedger memLedger, SwitchLedgerSettings settings, Clock clock) {
        return over(memLedger, settings, clock).reaper();
    }

    /** The base's ledger port over the MemLedger: what the {@code SessionFlowKit} is handed. */
    public static LedgerPort memLedger(MemLedger memLedger, SwitchLedgerSettings settings, Clock clock) {
        return over(memLedger, settings, clock).port();
    }
}
