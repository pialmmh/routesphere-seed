package com.telcobright.seed.sessionflow.api;

/**
 * The deadline of every state of a call, in seconds. Every deadline ends the call in FAILED with a named cause, and the
 * CDR is still written.
 *
 * @param preprocessingSec the task and its candidates must be ready within this ({@code PREPROCESS_TIMEOUT})
 * @param admittingSec     the admission must answer within this ({@code ADMISSION_TIMEOUT})
 * @param admittedSec      the signaling must answer within this ({@code NO_ANSWER}). It is the ONE deadline of the whole
 *                         pre-answer phase: the supervisor has no ringing state, so progress does not re-arm it — a call
 *                         switch sets it to cover the carrier's silence AND the far end's ringing together
 * @param ringingSec       the ringing window the application's own signaling child gives the far end after the first
 *                         progress — the child's deadline, not the supervisor's (the switch's ESL leg: 90 s). 0 = the
 *                         application has no ringing phase (an ad view, an SMS)
 * @param activeMaxSec     the longest a call may stay answered ({@code MAX_DURATION_REACHED})
 * @param tearingDownSec   the settlement must finish within this ({@code SETTLE_TIMEOUT})
 */
public record SessionFlowTimings(long preprocessingSec, long admittingSec, long admittedSec, long ringingSec, long activeMaxSec,
                              long tearingDownSec) {

    public SessionFlowTimings {
        if (preprocessingSec <= 0 || admittingSec <= 0 || admittedSec <= 0 || activeMaxSec <= 0 || tearingDownSec <= 0) {
            throw new IllegalArgumentException("every deadline but ringingSec must be positive");
        }
        if (ringingSec < 0) throw new IllegalArgumentException("ringingSec must be 0 (no ringing phase) or positive");
    }

    /**
     * The call switch's own deadlines: 3 s, 5 s, 120 s before the answer (its 30 s to the first progress and 90 s of ringing
     * in the supervisor's one window; the ESL leg keeps the two apart), one hour answered, 10 s to settle.
     */
    public static SessionFlowTimings defaults() { return new SessionFlowTimings(3, 5, 120, 90, 3600, 10); }

    /** The application's signaling child gives the far end a ringing window of its own. The supervisor's graph is the same either way. */
    public boolean hasRingingPhase() { return ringingSec > 0; }
}
