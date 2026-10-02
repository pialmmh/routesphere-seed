package com.telcobright.seed.callflow.api;

import com.telcobright.statewalk.event.StatemachineEvent;

/**
 * What a step of a {@link CallFlow} may do with the running machine of its call: send an event onto the call's bus and
 * manage the call's children. The flow itself never holds a machine — a machine is pooled, the flow is shared.
 */
public interface CallMachine {

    String callId();

    /** Send an event onto this call's bus: the supervisor handles it itself or forwards it to a child, by its routes. */
    void publish(StatemachineEvent event);

    /** Start a child machine of a registered type with its own context (it shares the call's history by reference). */
    void spawnChild(String childType, Object childContext);

    /** Retire every live child of this call — before a signaling retry starts new ones. */
    void retireChildren();
}
