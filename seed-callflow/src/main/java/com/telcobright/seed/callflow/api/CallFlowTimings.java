package com.telcobright.seed.callflow.api;

/**
 * The deadline of every state of a call, in seconds. Every deadline ends the call in FAILED with a named cause, and the
 * CDR is still written.
 *
 * @param preprocessingSec the task and its candidates must be ready within this ({@code PREPROCESS_TIMEOUT})
 * @param admittingSec     the admission must answer within this ({@code ADMISSION_TIMEOUT})
 * @param admittedSec      the signaling must make progress or answer within this ({@code NO_ANSWER})
 * @param ringingSec       once progress was reported, the answer must come within this ({@code NO_ANSWER}).
 *                         0 = the application has no ringing phase: progress does not open a second window
 * @param activeMaxSec     the longest a call may stay answered ({@code MAX_DURATION_REACHED})
 * @param tearingDownSec   the settlement must finish within this ({@code SETTLE_TIMEOUT})
 */
public record CallFlowTimings(long preprocessingSec, long admittingSec, long admittedSec, long ringingSec, long activeMaxSec,
                              long tearingDownSec) {

    public CallFlowTimings {
        if (preprocessingSec <= 0 || admittingSec <= 0 || admittedSec <= 0 || activeMaxSec <= 0 || tearingDownSec <= 0) {
            throw new IllegalArgumentException("every deadline but ringingSec must be positive");
        }
        if (ringingSec < 0) throw new IllegalArgumentException("ringingSec must be 0 (no ringing phase) or positive");
    }

    /** The call switch's own deadlines: 3 s, 5 s, 30 s to first progress, 90 s of ringing, one hour answered, 10 s to settle. */
    public static CallFlowTimings defaults() { return new CallFlowTimings(3, 5, 30, 90, 3600, 10); }

    public boolean hasRingingPhase() { return ringingSec > 0; }
}
