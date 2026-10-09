package com.telcobright.seed.sessionflow.api;

import com.telcobright.statewalk.event.StatemachineEvent;

/**
 * What a step of a {@link SessionFlow} may do with the running machine of its call: send an event onto the call's bus and
 * manage the call's children. The flow itself never holds a machine — a machine is pooled, the flow is shared.
 */
public interface SessionMachine {

    String callId();

    /** Send an event onto this call's bus: the supervisor handles it itself or forwards it to a child, by its routes. */
    void publish(StatemachineEvent event);

    /**
     * Start a child machine of a registered type with its own context (it shares the call's history by reference). The base remembers
     * the type as the attempt's: a retry retires it.
     */
    void spawnChild(String childType, Object childContext);

    /**
     * Retire the children the application spawned for this attempt (its signaling) — before another attempt starts new ones. The base's
     * own balance child is not among them: it outlives every attempt.
     */
    void retireChildren();
}
