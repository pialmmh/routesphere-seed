package com.telcobright.seed.sessionflow.spi;

import com.telcobright.seed.sessionflow.api.RoutesphereContext;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;

import java.util.ServiceLoader;

/**
 * L2a · Makes ONE lane's {@link MoneyEngine} — on the lane's own rules ({@link MoneyRules}: seed-prepaid's Prepaid, the flow's
 * {@code moneyRules()}), else on its answers ({@code flow.answers()}: {@code rateAtLevel}, {@code refundPolicy}, {@code settleOutcome},
 * {@code billedUnits}, {@code rateNextWindow}, …): one engine either way. Found on the classpath by {@code java.util.ServiceLoader}, as a
 * {@link LedgerMaker} is (design §11): seed-prepaid's. None on the classpath → the start is refused in words ({@link #NONE}); the base
 * never moves money itself.
 */
public interface MoneyEngineMaker {

    <C extends RoutesphereContext> MoneyEngine<C> make(RoutesphereFlow<C> flow);

    /** The maker the classpath carries (the first found), else {@link #NONE}. */
    static MoneyEngineMaker onTheClasspath() {
        return ServiceLoader.load(MoneyEngineMaker.class).findFirst().orElse(NONE);
    }

    /** No engine on the classpath: every lane is refused in words — at its engine's start, or at the first money step of a flow run by hand. */
    MoneyEngineMaker NONE = new MoneyEngineMaker() {
        @Override
        public <C extends RoutesphereContext> MoneyEngine<C> make(RoutesphereFlow<C> flow) {
            throw new IllegalStateException("[" + flow.name() + "] no money engine on the classpath: seed-prepaid is not on the classpath — add it"
                + " (the Prepaid engine over the kit's one ledger port, design prepaid-engine-design.md §11); the base never moves money itself");
        }
    };
}
