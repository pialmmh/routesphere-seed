package com.telcobright.seed.callflow.api;

/**
 * The pool of one application's call machines, right now.
 *
 * @param size          the fixed number of machines: the most calls that can be live at once
 * @param live          calls that hold a machine now
 * @param machinesBuilt machines ever built. It never passes {@code size} unless a machine failed its reset and was dropped
 * @param launched      calls that got a machine, since start
 * @param busy          calls refused at the door because the pool was full, since start
 * @param ended         calls that reached their end, since start
 * @param cdrPublished  calls whose CDR went to the sink
 * @param cdrLost       calls whose CDR had no tenant to be written on
 * @param owed          tier settlements and releases the ledger did not take
 * @param slotsHeld     channel slots held by live calls
 */
public record PoolStats(int size, int live, long machinesBuilt, long launched, long busy, long ended, long cdrPublished, long cdrLost,
                        long owed, int slotsHeld) {}
