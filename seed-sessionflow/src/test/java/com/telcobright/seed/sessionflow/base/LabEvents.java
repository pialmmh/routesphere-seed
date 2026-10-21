package com.telcobright.seed.sessionflow.base;

import com.telcobright.statewalk.event.StatemachineEvent;

/** The lab wire's frames, decoded: records with components. */
public final class LabEvents {
    private LabEvents() {}

    /** The first frame of a session: the kit takes the supervisor and the signaling machine for it. */
    public record Dial(LabFacts facts) implements StatemachineEvent {
        @Override public boolean isFirst() { return true; }
    }
    public record Ring(long atMs) implements StatemachineEvent {}
    public record Answer(long atMs) implements StatemachineEvent {}
    public record Fail(String cause, int code) implements StatemachineEvent {}
    public record Hangup(long atMs) implements StatemachineEvent {}
}
