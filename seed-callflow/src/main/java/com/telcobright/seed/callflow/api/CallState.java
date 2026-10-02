package com.telcobright.seed.callflow.api;

/**
 * The states of a call — the same graph for every application:
 *
 * <pre>
 *   PREPROCESSING → ADMITTING → ADMITTED → (RINGING) → ACTIVE → TEARING_DOWN → SUCCEEDED
 *        │              │           │           │          │           │
 *        └──────────────┴───────────┴───────────┴──────────┴───────────┴────→ FAILED
 *                                   └───────────┴─ ended before any service, by design → DEFERRED
 * </pre>
 */
public final class CallState {

    private CallState() {}

    /** The task and its candidates are worked out. A machine taken from the pool always starts here. */
    public static final String PREPROCESSING = "PREPROCESSING";
    /** The partner is identified, every tier is authorized, rated and reserved, the route is resolved. */
    public static final String ADMITTING = "ADMITTING";
    /** The signaling runs; nothing came back yet. */
    public static final String ADMITTED = "ADMITTED";
    /** The far end reported progress; the answer is awaited in its own, longer window. */
    public static final String RINGING = "RINGING";
    /** Answered: the service runs. */
    public static final String ACTIVE = "ACTIVE";
    /** The service is stopped and every tier is settled. */
    public static final String TEARING_DOWN = "TEARING_DOWN";

    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String DEFERRED = "DEFERRED";
}
