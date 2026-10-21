package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.statewalk.session.SessionSupervisor;
import com.telcobright.statewalk.session.SessionTimings;

/**
 * The process-wide knobs of one application's flow: the pool, statewalk's state timers, the budget child's cadence, the debug switch.
 * They come from the product's profile file; nothing here is read from the environment.
 *
 * @param pool                   the fixed number of sessions live at once — every machine of a session comes from a pool this big; a
 *                               session that finds it full is refused at the door ({@code CAPACITY_EXCEEDED}), it never waits
 * @param threads                the registry's timer threads
 * @param globalTimeoutSec       statewalk's lifetime cap ({@code TIMEOUT_GLOBAL}): longer than the longest healthy session
 * @param timings                statewalk's state timers: INIT, ADMITTING, CONNECTING, ACTIVE's maximum (seconds)
 * @param reservePeriodSec       the budget child renews every tier's reserve this often while ACTIVE (C14). 0 = no budget child: one
 *                               reserve at admission, settled at the end (an ad view, an SMS). §L.2: every renewal rewrites the session's
 *                               row, so a persisting lane renews at most once a minute (the engine refuses less when the store is on)
 * @param reserveInitialDelaySec the first renewal after the answer; 0 = one period (the call switch: 58 s, then every 60 s)
 * @param slotReconcileSec       how often the channel slots are checked against the live sessions. 0 = never
 * @param debug                  true = every step of every session is timed and written to its history and the log
 * @param admissionReserveMs     how long before ADMITTING's deadline the PAID candidates stop: the admission's budget is the deadline
 *                               minus this — the time kept for a free candidate and for the answer
 */
public record RoutesphereSettings(int pool, int threads, long globalTimeoutSec, SessionTimings timings, long reservePeriodSec,
                                  long reserveInitialDelaySec, long slotReconcileSec, boolean debug, long admissionReserveMs) {

    /** The reserve of {@link #defaults()}: half a second. */
    public static final long DEFAULT_ADMISSION_RESERVE_MS = 500;
    /** §L.2: a persisting lane renews at most once per minute (every renewal tick rewrites the whole session row). */
    public static final long MIN_PERSISTED_RENEWAL_SEC = 60;

    public RoutesphereSettings {
        if (pool <= 0) throw new IllegalArgumentException("pool must be positive");
        if (threads <= 0) throw new IllegalArgumentException("threads must be positive");
        if (timings == null) throw new IllegalArgumentException("timings are required");
        if (reservePeriodSec < 0 || reserveInitialDelaySec < 0 || slotReconcileSec < 0) throw new IllegalArgumentException("a period cannot be negative");
        if (admissionReserveMs < 0 || admissionReserveMs >= timings.admittingSec() * 1000) {
            throw new IllegalArgumentException("admissionReserveMs (" + admissionReserveMs + " ms) must be zero or more and shorter than the ADMITTING timer ("
                + timings.admittingSec() + " s): it would leave the paid candidates no time at all");
        }
        long longestHealthy = timings.initSec() + timings.admittingSec() + SessionSupervisor.ROUTING_SEC + timings.connectingSec() + timings.activeMaxSec();
        if (globalTimeoutSec <= longestHealthy) {
            throw new IllegalArgumentException("globalTimeoutSec (" + globalTimeoutSec + " s) must be longer than the longest healthy session ("
                + longestHealthy + " s = the sum of the state timers): it would cut a good session as a hung one");
        }
    }

    /** A pool of 1000; INIT 3 s, ADMITTING 5 s, CONNECTING 120 s, ACTIVE one hour; the cap at two hours; no budget child; slots each minute. */
    public static RoutesphereSettings defaults() {
        return new RoutesphereSettings(1000, 2, 7200, new SessionTimings(3, 5, 120, 3600), 0, 0, 60, false, DEFAULT_ADMISSION_RESERVE_MS);
    }

    public RoutesphereSettings withPool(int v) { return new RoutesphereSettings(v, threads, globalTimeoutSec, timings, reservePeriodSec, reserveInitialDelaySec, slotReconcileSec, debug, admissionReserveMs); }
    public RoutesphereSettings withThreads(int v) { return new RoutesphereSettings(pool, v, globalTimeoutSec, timings, reservePeriodSec, reserveInitialDelaySec, slotReconcileSec, debug, admissionReserveMs); }
    public RoutesphereSettings withGlobalTimeoutSec(long v) { return new RoutesphereSettings(pool, threads, v, timings, reservePeriodSec, reserveInitialDelaySec, slotReconcileSec, debug, admissionReserveMs); }
    public RoutesphereSettings withTimings(SessionTimings v) { return new RoutesphereSettings(pool, threads, globalTimeoutSec, v, reservePeriodSec, reserveInitialDelaySec, slotReconcileSec, debug, admissionReserveMs); }
    public RoutesphereSettings withReservePeriodSec(long v) { return new RoutesphereSettings(pool, threads, globalTimeoutSec, timings, v, reserveInitialDelaySec, slotReconcileSec, debug, admissionReserveMs); }
    public RoutesphereSettings withReserveInitialDelaySec(long v) { return new RoutesphereSettings(pool, threads, globalTimeoutSec, timings, reservePeriodSec, v, slotReconcileSec, debug, admissionReserveMs); }
    public RoutesphereSettings withSlotReconcileSec(long v) { return new RoutesphereSettings(pool, threads, globalTimeoutSec, timings, reservePeriodSec, reserveInitialDelaySec, v, debug, admissionReserveMs); }
    public RoutesphereSettings withDebug(boolean v) { return new RoutesphereSettings(pool, threads, globalTimeoutSec, timings, reservePeriodSec, reserveInitialDelaySec, slotReconcileSec, v, admissionReserveMs); }
    public RoutesphereSettings withAdmissionReserveMs(long v) { return new RoutesphereSettings(pool, threads, globalTimeoutSec, timings, reservePeriodSec, reserveInitialDelaySec, slotReconcileSec, debug, v); }

    /** The budget of one admission: the ADMITTING timer minus the reserve. */
    public long admissionBudgetMs() { return timings.admittingSec() * 1000 - admissionReserveMs; }

    /** When the first renewal comes after the answer: the initial delay when one is set, else one period. */
    public long firstRenewalSec() { return reserveInitialDelaySec > 0 ? reserveInitialDelaySec : reservePeriodSec; }

    /** True = the flow has a budget child (the call, the WiFi session): it renews every tier while ACTIVE. */
    public boolean renews() { return reservePeriodSec > 0; }
}
