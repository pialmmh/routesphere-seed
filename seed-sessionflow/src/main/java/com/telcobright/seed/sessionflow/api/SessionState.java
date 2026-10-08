package com.telcobright.seed.sessionflow.api;

/**
 * The states of a call — the same graph for every application, the library's session graph with the base's preprocessing in
 * front. No protocol word (ringing, playing, submitting) is a state: those are the signaling child's, reported as progress.
 *
 * <pre>
 *   PREPROCESSING → ADMITTING → ADMITTED → ACTIVE → TEARING_DOWN → SUCCEEDED
 *        │              │           │          │           │
 *        └──────────────┴───────────┴──────────┴───────────┴────→ FAILED
 *                                   └─ ended before any service, by design → DEFERRED
 * </pre>
 */
public final class SessionState {

    private SessionState() {}

    /** The task and its candidates are worked out. A machine taken from the pool always starts here. */
    public static final String PREPROCESSING = "PREPROCESSING";
    /** The partner is identified, every tier is authorized, rated and reserved, the route is resolved. */
    public static final String ADMITTING = "ADMITTING";
    /** The signaling runs: the answer is awaited; the far end's progress (ringing, early media) is a stay here. */
    public static final String ADMITTED = "ADMITTED";
    /** Answered: the service runs. */
    public static final String ACTIVE = "ACTIVE";
    /** The service is stopped and every tier is settled. */
    public static final String TEARING_DOWN = "TEARING_DOWN";

    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String DEFERRED = "DEFERRED";
}
