package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.RoutesphereSupervisor;

import java.util.function.Supplier;

/** The lab's supervisor class (R6): its flow's name (a lab flow may be named for another lane's table) and statewalk's final SessionSupervisor. */
public final class LabSupervisor extends RoutesphereSupervisor<LabContext> {

    public LabSupervisor(LabFlow flow) { this(flow, LabContext::new); }

    public LabSupervisor(LabFlow flow, Supplier<LabContext> contexts) { super(flow, contexts); }
}
