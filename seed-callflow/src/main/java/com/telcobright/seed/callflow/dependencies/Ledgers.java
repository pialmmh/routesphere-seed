package com.telcobright.seed.callflow.dependencies;

import com.telcobright.seed.callflow.internal.OrchestrixLedger;
import com.telcobright.seed.callflow.internal.OwedJournal;
import com.telcobright.seed.callflow.spi.LedgerPort;

import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Function;

/** The ledgers a host can choose from. */
public final class Ledgers {

    private Ledgers() {}

    /**
     * The ledger on orchestrix portal-api's prepaid roads.
     *
     * @param environment the process environment by variable NAME: the bearer is read from it once, here
     * @param owedFile    where a reserve that orchestrix cannot give back yet is written down, one line per case
     */
    public static LedgerPort orchestrix(LedgerSettings settings, Function<String, String> environment, Path owedFile, Clock clock) {
        return new OrchestrixLedger(settings, environment, new OwedJournal(owedFile, clock));
    }
}
