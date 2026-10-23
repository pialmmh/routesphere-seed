package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.seed.sessionflow.api.RoutesphereSupervisor;

import java.util.function.Supplier;

/** The supervisor class of the voice call (ESL) (R6): the lane's name and statewalk's final SessionSupervisor — no logic of its own (its answers are the flow's). */
public final class CallSupervisor extends RoutesphereSupervisor<VoiceContext> {

    public CallSupervisor(VoiceFlow flow) { this(flow, VoiceContext::new); }

    /** A bed that keeps its contexts hands its own supplier. */
    public CallSupervisor(VoiceFlow flow, Supplier<VoiceContext> contexts) { super(flow, contexts); }
}
