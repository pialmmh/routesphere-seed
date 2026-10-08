package com.telcobright.seed.sessionflow.api;

/**
 * The causes every call can end with, whatever the application — the string on the CDR's {@code hangupCause}. They are
 * plain strings, not an enum, so an application adds its own words (the ad: {@code NO_RULE}, {@code NOT_SHOWN}; the
 * voice call: {@code DIGIT_FILTER_DENIED}, {@code INVALID_DID}) without touching the library.
 */
public final class SessionCause {

    private SessionCause() {}

    public static final String NORMAL_CLEARING = "NORMAL_CLEARING";

    // ── PREPROCESSING ──
    public static final String PREPROCESS_TIMEOUT = "PREPROCESS_TIMEOUT";
    public static final String TENANT_UNAVAILABLE = "TENANT_UNAVAILABLE";
    /** The application named candidates and none of them could be tried. */
    public static final String NO_CANDIDATE = "NO_CANDIDATE";

    // ── ADMITTING ──
    public static final String PARTNER_NOT_FOUND = "PARTNER_NOT_FOUND";
    public static final String PARTNER_DEACTIVATED = "PARTNER_DEACTIVATED";
    public static final String CHANNEL_LIMIT_REACHED = "CHANNEL_LIMIT_REACHED";
    /** A tier has no rate for this call. */
    public static final String UNRATED = "UNRATED";
    public static final String INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";
    /** The ledger itself failed. Never reported as a customer's money cause. */
    public static final String BILLING_SYSTEM_ERROR = "BILLING_SYSTEM_ERROR";
    public static final String NO_ROUTE = "NO_ROUTE";
    public static final String ADMISSION_TIMEOUT = "ADMISSION_TIMEOUT";

    // ── the life of the call ──
    /** The signaling window closed with no answer (the ad names it {@code NOT_SHOWN}). */
    public static final String NO_ANSWER = "NO_ANSWER";
    /** A periodic reserve could not be renewed at some tier: the call is cut. */
    public static final String BALANCE_EXHAUSTED = "BALANCE_EXHAUSTED";
    public static final String MAX_DURATION_REACHED = "MAX_DURATION_REACHED";
    public static final String SETTLE_TIMEOUT = "SETTLE_TIMEOUT";

    // ── the machine ──
    /** The registry's global timeout killed a machine that no state timeout had ended. */
    public static final String HUNG_MACHINE = "HUNG_MACHINE";
    /**
     * The process that ran the call stopped while the call was in the air (R1-6): the next start published its record from the
     * journal of the calls in the air — a call that was handed over, every tier charged what it reserved.
     */
    public static final String LOST_AT_RESTART = "LOST_AT_RESTART";
    public static final String SYSTEM_SHUTDOWN = "SYSTEM_SHUTDOWN";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** The pool is full: the call never got a machine. A counter, never a CDR. */
    public static final String BUSY = "BUSY";

    /** A fault of the platform, not of the customer: it must never be shown as a balance or a routing cause. */
    public static boolean isSystemFault(String cause) {
        return BILLING_SYSTEM_ERROR.equals(cause) || TENANT_UNAVAILABLE.equals(cause) || INTERNAL_ERROR.equals(cause);
    }
}
