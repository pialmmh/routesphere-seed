package com.telcobright.seed.sessionflow.api;

/**
 * The pool of one application's call machines, right now.
 *
 * @param size          the fixed number of machines: the most calls that can be live at once
 * @param live          the registry's live CELLS now (kept as it was): every machine of every live session — its supervisor, its
 *                      signaling machine and its helper children (two per session in a lane without children, three with the budget
 *                      child). The sessions are {@code sessions}
 * @param machinesBuilt the supervisors the registry's pool ever built — statewalk's own count (P1-F; one per live session at most). It
 *                      never passes {@code size} unless a machine failed its reset and was dropped
 * @param launched      calls that got a machine, since start
 * @param busy          NEW sessions refused at the door because every machine was live, since start — counted at the lane's channel's
 *                      inbound and at {@code RoutesphereEngine.launch} (P1-F), never at {@code deliver}
 * @param ended         calls that reached their end, since start
 * @param cdrPublished  calls whose CDR went to the sink
 * @param cdrLost       calls whose CDR had no tenant to be written on
 * @param owed          tier settlements and releases the ledger did not take
 * @param slotsHeld     channel slots held by live calls
 * @param sessions      the sessions live now (P1-F): one per session id, whatever its cells; {@link #NOT_COUNTED} in a record made with
 *                      the ten numbers of 2.0
 */
public record PoolStats(int size, int live, long machinesBuilt, long launched, long busy, long ended, long cdrPublished, long cdrLost,
                        long owed, int slotsHeld, int sessions) {

    /** {@code sessions} of a record made with the ten numbers of 2.0: not counted. */
    public static final int NOT_COUNTED = -1;

    /** The ten numbers of 2.0, kept (P1-F): {@code sessions} not counted ({@link #NOT_COUNTED}). */
    public PoolStats(int size, int live, long machinesBuilt, long launched, long busy, long ended, long cdrPublished, long cdrLost,
                     long owed, int slotsHeld) {
        this(size, live, machinesBuilt, launched, busy, ended, cdrPublished, cdrLost, owed, slotsHeld, NOT_COUNTED);
    }
}
