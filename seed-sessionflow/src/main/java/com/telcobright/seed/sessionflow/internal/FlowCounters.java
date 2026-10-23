package com.telcobright.seed.sessionflow.internal;

import java.util.concurrent.atomic.AtomicLong;

/** The running numbers of one application's call flow. Counters only: nothing here decides anything. */
public final class FlowCounters {

    /**
     * Not counted here, kept so no reader breaks (P1-F): the base builds no machine — statewalk's pools do, and count them. Read
     * {@code RoutesphereEngine.stats().machinesBuilt()}: the supervisors' pool's own count. This one stays 0.
     *
     * @deprecated statewalk's registry counts the machines its pools build: {@code registry().poolStatistics(type).totalCreated()}
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public final AtomicLong machinesBuilt = new AtomicLong();
    public final AtomicLong launched = new AtomicLong();
    /**
     * NEW sessions refused at the door because every machine was live (statewalk's CAPACITY_EXCEEDED). They never got a machine and have
     * no CDR. Counted by the engine where it sees the refusal (P1-F): the lane's channel's inbound and {@code RoutesphereEngine.launch} —
     * never {@code deliver}, so a lane that counts its own refusals there is not counted twice.
     */
    public final AtomicLong busy = new AtomicLong();
    public final AtomicLong ended = new AtomicLong();
    public final AtomicLong cdrPublished = new AtomicLong();
    /** Calls whose CDR could not be written on any tenant. */
    public final AtomicLong cdrLost = new AtomicLong();
    /** Tier settlements or releases the ledger did not take. */
    public final AtomicLong owed = new AtomicLong();
    /** Calls not handed over because their line in the journal of the calls in the air could not be written (R1-6). */
    public final AtomicLong journalRefused = new AtomicLong();
}
