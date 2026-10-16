package com.telcobright.seed.sessionflow.api;

/**
 * What a session came to, as its settlement reads it: answered by {@link SessionFlowSteps#settleOutcome} and priced by the
 * lane's {@link com.telcobright.seed.sessionflow.dependencies.RefundPolicy}.
 */
public enum SettleOutcome {
    /** The service ran: the call was answered, the ad was shown. */
    CONSUMED,
    /** The ad: handed to the phone, never shown. */
    OFFERED_NOT_CONSUMED,
    /** Admitted, and the service never ran: the call nobody answered. */
    FAILED
}
