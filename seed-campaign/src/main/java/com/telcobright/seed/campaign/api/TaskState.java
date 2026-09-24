package com.telcobright.seed.campaign.api;

/**
 * {@code campaign_task.STATE}, routesphere's codes (grep over routesphere-core, 2026-09-24). A kind uses the codes
 * whose meaning fits it: an SMS task is CREATED, then PROCESSING, then SENT or FAILED (or RETRY); an ad task is born
 * PROCESSING (the view is live), then SENT (= completed) or FAILED (abandoned, stalled, never shown).
 */
public enum TaskState {
    PENDING(0), PROCESSING(1), QUEUED(4), FAILED(5), CREATED(6), SENT(11), RETRY(16);

    private final int code;

    TaskState(int code) { this.code = code; }

    public int code() { return code; }

    public boolean terminal() { return this == SENT || this == FAILED; }

    public static TaskState of(int code) {
        for (TaskState s : values()) if (s.code == code) return s;
        throw new IllegalArgumentException("no campaign_task STATE " + code);
    }
}
