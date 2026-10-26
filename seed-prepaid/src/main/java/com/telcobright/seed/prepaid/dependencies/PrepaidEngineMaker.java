package com.telcobright.seed.prepaid.dependencies;

import com.telcobright.seed.prepaid.internal.PrepaidEngine;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.spi.MoneyEngine;
import com.telcobright.seed.sessionflow.spi.MoneyEngineMaker;

/**
 * seed-prepaid's maker of a lane's money engine, found by the base's kit on the classpath ({@code java.util.ServiceLoader}, the
 * registration in {@code META-INF/services}) — design §11: the base never moves money itself, the kit finds this maker.
 */
public final class PrepaidEngineMaker implements MoneyEngineMaker {

    @Override
    public <C extends RoutesphereContext> MoneyEngine<C> make(RoutesphereFlow<C> flow) { return new PrepaidEngine<>(flow.answers()); }
}
