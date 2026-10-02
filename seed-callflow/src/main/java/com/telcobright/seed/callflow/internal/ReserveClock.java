package com.telcobright.seed.callflow.internal;

import com.telcobright.seed.callflow.publishes.ReserveTick;
import com.telcobright.statewalk.machine.Machine;
import com.telcobright.statewalk.state.StateMap;

import java.util.concurrent.TimeUnit;

/**
 * The reserve clock of one answered call: a child machine that publishes a {@link ReserveTick} every reserve period.
 * It is spawned when the call becomes ACTIVE and retired with the call. It keeps nothing: the period is the process's
 * setting, the same for every call.
 */
public final class ReserveClock extends Machine<ReserveClock.Started> {

    public static final String TYPE = "ReserveClock";
    private static final String TICKING = "TICKING";
    private static final String STOPPED = "STOPPED";

    /** The clock's context: when it was started, nothing else. */
    public record Started(long atMs) {}

    private final long periodSec;

    public ReserveClock(long periodSec) { this.periodSec = periodSec; }

    @Override
    protected StateMap defineStates() {
        return StateMap.builder()
            .initialState(TICKING)
            .state(TICKING)
                .interim()
                .timeoutStay(periodSec, TimeUnit.SECONDS, self -> ((ReserveClock) self).publishEvent(new ReserveTick()))
            .state(STOPPED)
                .finalState()
                .timeout(1, TimeUnit.SECONDS, STOPPED)
            .build();
    }
}
