package com.telcobright.seed.prepaid.parity;

import com.telcobright.seed.sessionflow.api.RoutesphereContext;

/** The parity call's context: the base's, and nothing of its own (the numbers live on the base's fields; a Jackson POJO). */
public class CallContext extends RoutesphereContext {
    public CallContext() {}
}
