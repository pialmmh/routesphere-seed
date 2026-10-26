package com.telcobright.seed.prepaid.parity;

import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.RoutesphereSupervisor;

import java.util.function.Supplier;

/** The parity call's supervisor class (R6): named for the live call's table, {@code CallSupervisor}. */
public final class ParitySupervisor extends RoutesphereSupervisor<CallContext> {
    public ParitySupervisor(RoutesphereFlow<CallContext> flow, Supplier<CallContext> contexts) { super(flow, contexts); }
}
