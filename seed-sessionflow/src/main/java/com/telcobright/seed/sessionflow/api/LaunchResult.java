package com.telcobright.seed.sessionflow.api;

import com.telcobright.statewalk.registry.RejectCause;

/**
 * What the door answered when a call asked for a machine. A launched call always ends with a CDR. A call that was not
 * launched never had a machine: it has no CDR, only a counter.
 *
 * @param cause  null when launched; else {@link SessionCause#BUSY} (the pool is full), or the registry's own word
 * @param reason the registry's reason, for the log
 */
public record LaunchResult(boolean launched, String cause, RejectCause reason) {

    static LaunchResult ok() { return new LaunchResult(true, null, null); }

    static LaunchResult refused(RejectCause reason) {
        boolean full = reason == RejectCause.CAPACITY_EXCEEDED;
        return new LaunchResult(false, full ? SessionCause.BUSY : reason.name(), reason);
    }

    public boolean busy() { return SessionCause.BUSY.equals(cause); }
}
