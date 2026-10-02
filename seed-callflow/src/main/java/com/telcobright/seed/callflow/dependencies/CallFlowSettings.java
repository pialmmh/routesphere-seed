package com.telcobright.seed.callflow.dependencies;

import com.telcobright.seed.callflow.api.CallFlowTimings;

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
 */
public record CallFlowSettings(int pool, int threads, long globalTimeoutSec, CallFlowTimings timings, long reservePeriodSec,
                               long slotReconcileSec, boolean debug) {

    public CallFlowSettings {
        if (pool <= 0) throw new IllegalArgumentException("pool must be positive");
        if (threads <= 0) throw new IllegalArgumentException("threads must be positive");
        if (timings == null) throw new IllegalArgumentException("timings are required");
        if (reservePeriodSec < 0 || slotReconcileSec < 0) throw new IllegalArgumentException("a period cannot be negative");
        long longestHealthyCall = timings.preprocessingSec() + timings.admittingSec() + timings.admittedSec() + timings.ringingSec()
            + timings.activeMaxSec() + timings.tearingDownSec();
        if (globalTimeoutSec <= longestHealthyCall) {
            throw new IllegalArgumentException("globalTimeoutSec (" + globalTimeoutSec + " s) must be longer than the longest healthy call ("
                + longestHealthyCall + " s = the sum of the state deadlines): it would cut a good call as a hung machine");
        }
    }

    /** A pool of 1000, the call switch's deadlines, the killer at two hours, no periodic reserve, slots checked each minute. */
    public static CallFlowSettings defaults() {
        return new CallFlowSettings(1000, 2, 7200, CallFlowTimings.defaults(), 0, 60, false);
    }

    public CallFlowSettings withPool(int newPool) {
        return new CallFlowSettings(newPool, threads, globalTimeoutSec, timings, reservePeriodSec, slotReconcileSec, debug);
    }

    public CallFlowSettings withThreads(int newThreads) {
        return new CallFlowSettings(pool, newThreads, globalTimeoutSec, timings, reservePeriodSec, slotReconcileSec, debug);
    }

    public CallFlowSettings withGlobalTimeoutSec(long seconds) {
        return new CallFlowSettings(pool, threads, seconds, timings, reservePeriodSec, slotReconcileSec, debug);
    }

    public CallFlowSettings withTimings(CallFlowTimings newTimings) {
        return new CallFlowSettings(pool, threads, globalTimeoutSec, newTimings, reservePeriodSec, slotReconcileSec, debug);
    }

    public CallFlowSettings withReservePeriodSec(long seconds) {
        return new CallFlowSettings(pool, threads, globalTimeoutSec, timings, seconds, slotReconcileSec, debug);
    }

    public CallFlowSettings withSlotReconcileSec(long seconds) {
        return new CallFlowSettings(pool, threads, globalTimeoutSec, timings, reservePeriodSec, seconds, debug);
    }

    public CallFlowSettings withDebug(boolean on) {
        return new CallFlowSettings(pool, threads, globalTimeoutSec, timings, reservePeriodSec, slotReconcileSec, on);
    }
}
