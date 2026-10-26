package com.telcobright.seed.prepaid.dependencies;

import com.telcobright.seed.prepaid.api.Prepaid;
import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.spi.MoneyEngine;
import com.telcobright.seed.sessionflow.spi.MoneyEngineMaker;
import com.telcobright.seed.sessionflow.spi.MoneyRules;

/**
 * seed-prepaid's maker of a lane's money engine, found by the base's kit on the classpath ({@code java.util.ServiceLoader}, the
 * registration in {@code META-INF/services}) — design §11: the base never moves money itself. ONE engine: a lane that gives its own
 * Prepaid runs on it; a lane that gives none gets one built from its existing answers ({@link Prepaid#ofTheLanesAnswers}).
 */
public final class PrepaidEngineMaker implements MoneyEngineMaker {

    @Override
    public <C extends RoutesphereContext> MoneyEngine<C> make(RoutesphereFlow<C> flow) {
        LaneAnswers<C> lane = flow.answers();
        MoneyRules<C> own = lane.moneyRules();
        return (own != null ? own : Prepaid.<C>ofTheLanesAnswers(flow.kit())).engineFor(lane);
    }
}
