package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.seed.sessionflow.api.RoutesphereSupervisor;

import java.util.function.Supplier;

/** The supervisor class of the ad lane (R6): the lane's name and statewalk's final SessionSupervisor — no logic of its own (its answers are the flow's). */
public final class AdSupervisor extends RoutesphereSupervisor<AdContext> {

    public AdSupervisor(AdFlow flow) { this(flow, AdContext::new); }

    /** A bed that keeps its contexts hands its own supplier. */
    public AdSupervisor(AdFlow flow, Supplier<AdContext> contexts) { super(flow, contexts); }
}
