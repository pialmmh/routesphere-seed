package com.telcobright.seed.routing.policy;

/**
 * Someone else saved the policy first: the version the editor started from is no longer the stored one. The
 * screen reloads and the person looks again — a save never silently overwrites another person's change.
 */
public class PolicyConflictException extends RuntimeException {
    private final int storedVersion;

    public PolicyConflictException(String message, int storedVersion) {
        super(message);
        this.storedVersion = storedVersion;
    }

    /** The version the store holds now; 0 when the policy is gone. */
    public int storedVersion() { return storedVersion; }
}
