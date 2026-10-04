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
     * @param owedFile    the journal of what had to go back: a return that is being asked, and what orchestrix did not
     *                    give back, one line per fact
     */
    public static LedgerPort orchestrix(LedgerSettings settings, Function<String, String> environment, Path owedFile, Clock clock) {
        return orchestrix(settings, environment, owedFile, clock, ReturnPace.standard());
    }

    /** The same, with the pace of the return road said (how often a return is asked again, and after what waits). */
    public static LedgerPort orchestrix(LedgerSettings settings, Function<String, String> environment, Path owedFile, Clock clock, ReturnPace pace) {
        return new OrchestrixLedger(settings, environment, new OwedJournal(owedFile, clock), pace);
    }
}
