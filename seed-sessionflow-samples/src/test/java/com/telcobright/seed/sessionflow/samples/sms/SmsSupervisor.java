package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.RoutesphereSupervisor;

import java.util.function.Supplier;

/** The supervisor class of the SMS (R6): the lane's name and statewalk's final SessionSupervisor — no logic of its own (its answers are the flow's). */
public final class SmsSupervisor extends RoutesphereSupervisor<SmsContext> {

    public SmsSupervisor(RoutesphereFlow<SmsContext> flow) { this(flow, SmsContext::new); }

    /** A bed that keeps its contexts hands its own supplier. */
    public SmsSupervisor(RoutesphereFlow<SmsContext> flow, Supplier<SmsContext> contexts) { super(flow, contexts); }
}
