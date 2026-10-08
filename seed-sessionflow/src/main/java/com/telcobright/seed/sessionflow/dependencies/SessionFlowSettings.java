package com.telcobright.seed.sessionflow.dependencies;

import com.telcobright.seed.sessionflow.api.SessionFlowTimings;

/**
 * The process-wide knobs of one application's call flow: the pool, the deadlines, the debug switch. They come from the
 * product's profile file; nothing here is read from the environment.
 *
 * @param pool             the fixed number of machines — the most calls that can be live at once. A call that finds
 *                         the pool full is refused at the door ({@code BUSY}), it never waits
 * @param threads          the registry's timer threads
 * @param globalTimeoutSec the hung-machine killer: no call lives longer than this, whatever its state
 * @param timings          the deadline of every state
 * @param reservePeriodSec a long call renews its reserve every this many seconds while answered. 0 = one reserve at
 *                         admission, reconciled at the end (an SMS, an ad view)
 * @param slotReconcileSec how often the channel slots are checked against the live calls. 0 = never
 * @param debug            true = every step of every call is logged and timed; false = only refusals and failures
 * @param admissionReserveMs how long before ADMITTING's deadline the PAID tries stop. Admission has one budget: the
 *                         ADMITTING deadline minus this reserve. Every ledger call gets the smaller of its own timeout and
 *                         what is left of the budget; when the budget is spent only a free candidate is still tried.
 *                         The reserve is the time kept for that free candidate and for the answer
 * @param reserveInitialDelaySec when the FIRST renewal comes after the answer; the next ones come every {@code reservePeriodSec}.
 *                         0 = one period (the default — every flow before B10 unchanged). The call switch sets it from
 *                         {@code routesphere.billing.periodic-reserve.initial-delay-seconds} (58: "just under one unit so the
 *                         renewal precedes its expiry"). Without a reserve period it has no effect
 */
public record SessionFlowSettings(int pool, int threads, long globalTimeoutSec, SessionFlowTimings timings, long reservePeriodSec,
                               long slotReconcileSec, boolean debug, long admissionReserveMs, long reserveInitialDelaySec) {

    /** The reserve of {@link #defaults()}: half a second. */
    public static final long DEFAULT_ADMISSION_RESERVE_MS = 500;

    /** The shape before B10: the first renewal one period after the answer. */
    public SessionFlowSettings(int pool, int threads, long globalTimeoutSec, SessionFlowTimings timings, long reservePeriodSec,
                            long slotReconcileSec, boolean debug, long admissionReserveMs) {
        this(pool, threads, globalTimeoutSec, timings, reservePeriodSec, slotReconcileSec, debug, admissionReserveMs, 0);
    }

    /** The shape before the admission budget: the default reserve. */
    public SessionFlowSettings(int pool, int threads, long globalTimeoutSec, SessionFlowTimings timings, long reservePeriodSec,
                            long slotReconcileSec, boolean debug) {
        this(pool, threads, globalTimeoutSec, timings, reservePeriodSec, slotReconcileSec, debug, DEFAULT_ADMISSION_RESERVE_MS, 0);
    }

    public SessionFlowSettings {
        if (pool <= 0) throw new IllegalArgumentException("pool must be positive");
        if (threads <= 0) throw new IllegalArgumentException("threads must be positive");
        if (timings == null) throw new IllegalArgumentException("timings are required");
        if (reservePeriodSec < 0 || slotReconcileSec < 0) throw new IllegalArgumentException("a period cannot be negative");
        if (reserveInitialDelaySec < 0) throw new IllegalArgumentException("the first renewal's delay cannot be negative (0 = one period)");
        if (timings != null && (admissionReserveMs < 0 || admissionReserveMs >= timings.admittingSec() * 1000)) {
            throw new IllegalArgumentException("admissionReserveMs (" + admissionReserveMs + " ms) must be zero or more and shorter than the ADMITTING deadline ("
                + timings.admittingSec() + " s): it would leave the paid tries no time at all");
        }
        long longestHealthyCall = timings.preprocessingSec() + timings.admittingSec() + timings.admittedSec() + timings.ringingSec()
            + timings.activeMaxSec() + timings.tearingDownSec();
        if (globalTimeoutSec <= longestHealthyCall) {
            throw new IllegalArgumentException("globalTimeoutSec (" + globalTimeoutSec + " s) must be longer than the longest healthy call ("
                + longestHealthyCall + " s = the sum of the state deadlines): it would cut a good call as a hung machine");
        }
    }

    /** A pool of 1000, the call switch's deadlines, the killer at two hours, no periodic reserve, slots checked each minute. */
    public static SessionFlowSettings defaults() {
        return new SessionFlowSettings(1000, 2, 7200, SessionFlowTimings.defaults(), 0, 60, false, DEFAULT_ADMISSION_RESERVE_MS, 0);
    }

    public SessionFlowSettings withPool(int newPool) {
        return new SessionFlowSettings(newPool, threads, globalTimeoutSec, timings, reservePeriodSec, slotReconcileSec, debug, admissionReserveMs, reserveInitialDelaySec);
    }

    public SessionFlowSettings withThreads(int newThreads) {
        return new SessionFlowSettings(pool, newThreads, globalTimeoutSec, timings, reservePeriodSec, slotReconcileSec, debug, admissionReserveMs, reserveInitialDelaySec);
    }

    public SessionFlowSettings withGlobalTimeoutSec(long seconds) {
        return new SessionFlowSettings(pool, threads, seconds, timings, reservePeriodSec, slotReconcileSec, debug, admissionReserveMs, reserveInitialDelaySec);
    }

    public SessionFlowSettings withTimings(SessionFlowTimings newTimings) {
        return new SessionFlowSettings(pool, threads, globalTimeoutSec, newTimings, reservePeriodSec, slotReconcileSec, debug, admissionReserveMs, reserveInitialDelaySec);
    }

    public SessionFlowSettings withReservePeriodSec(long seconds) {
        return new SessionFlowSettings(pool, threads, globalTimeoutSec, timings, seconds, slotReconcileSec, debug, admissionReserveMs, reserveInitialDelaySec);
    }

    public SessionFlowSettings withSlotReconcileSec(long seconds) {
        return new SessionFlowSettings(pool, threads, globalTimeoutSec, timings, reservePeriodSec, seconds, debug, admissionReserveMs, reserveInitialDelaySec);
    }

    public SessionFlowSettings withDebug(boolean on) {
        return new SessionFlowSettings(pool, threads, globalTimeoutSec, timings, reservePeriodSec, slotReconcileSec, on, admissionReserveMs, reserveInitialDelaySec);
    }

    public SessionFlowSettings withAdmissionReserveMs(long millis) {
        return new SessionFlowSettings(pool, threads, globalTimeoutSec, timings, reservePeriodSec, slotReconcileSec, debug, millis, reserveInitialDelaySec);
    }

    /** B10 · The first renewal after the answer comes this many seconds after it; 0 = one period. */
    public SessionFlowSettings withReserveInitialDelaySec(long seconds) {
        return new SessionFlowSettings(pool, threads, globalTimeoutSec, timings, reservePeriodSec, slotReconcileSec, debug, admissionReserveMs, seconds);
    }

    /** The budget of one admission: the ADMITTING deadline minus the reserve. */
    public long admissionBudgetMs() { return timings.admittingSec() * 1000 - admissionReserveMs; }

    /** B10 · When the first renewal comes after the answer: the initial delay when one is set, else one reserve period. */
    public long firstRenewalSec() { return reserveInitialDelaySec > 0 ? reserveInitialDelaySec : reservePeriodSec; }
}
