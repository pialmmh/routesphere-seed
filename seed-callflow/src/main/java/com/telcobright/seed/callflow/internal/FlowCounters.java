package com.telcobright.seed.callflow.internal;

import java.util.concurrent.atomic.AtomicLong;

/** The running numbers of one application's call flow. Counters only: nothing here decides anything. */
public final class FlowCounters {

    /** Machines ever built for the pool. It stays at or below the pool's size unless a reset failed. */
    public final AtomicLong machinesBuilt = new AtomicLong();
    public final AtomicLong launched = new AtomicLong();
    /** Calls refused at the door because the pool was full. They never got a machine and have no CDR. */
    public final AtomicLong busy = new AtomicLong();
    public final AtomicLong ended = new AtomicLong();
    public final AtomicLong cdrPublished = new AtomicLong();
    /** Calls whose CDR could not be written on any tenant. */
    public final AtomicLong cdrLost = new AtomicLong();
    /** Tier settlements or releases the ledger did not take. */
    public final AtomicLong owed = new AtomicLong();
}
