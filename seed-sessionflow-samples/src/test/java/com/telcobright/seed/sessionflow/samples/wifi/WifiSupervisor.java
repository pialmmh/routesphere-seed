package com.telcobright.seed.sessionflow.samples.wifi;

import com.telcobright.seed.sessionflow.api.RoutesphereSupervisor;

import java.util.function.Supplier;

/** The supervisor class of the WiFi session (R6): the lane's name and statewalk's final SessionSupervisor — no logic of its own (its answers are the flow's). */
public final class WifiSupervisor extends RoutesphereSupervisor<WifiContext> {

    public WifiSupervisor(WifiFlow flow) { this(flow, WifiContext::new); }

    /** A bed that keeps its contexts hands its own supplier. */
    public WifiSupervisor(WifiFlow flow, Supplier<WifiContext> contexts) { super(flow, contexts); }
}
