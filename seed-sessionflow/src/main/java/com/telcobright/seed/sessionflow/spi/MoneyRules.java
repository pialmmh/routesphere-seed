package com.telcobright.seed.sessionflow.spi;

import com.telcobright.seed.sessionflow.api.LaneAnswers;
import com.telcobright.seed.sessionflow.api.RoutesphereContext;

/**
 * L2a · A lane's OWN money rules — seed-prepaid's {@code Prepaid}, built by the application ({@code Prepaid.builder(name)…build(kit)}) and
 * given to its flow ({@code RoutesphereApp.Builder.prepaid}, or a subclass's {@code moneyRules()}): the engine of the flow is made ON them.
 * A lane that gives none runs on its own answers ({@code rateAtLevel}, {@code refundPolicy}, …): one engine either way (design §11).
 *
 * @param <C> the lane's context
 */
public interface MoneyRules<C extends RoutesphereContext> {

    /** The money engine of ONE flow on these rules, reading the flow's answers. */
    MoneyEngine<C> engineFor(LaneAnswers<C> lane);
}
