package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.DryRun;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.api.SessionCause;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dry run on the new base (kept from the 3.x base; ad-sphere's routing admin and the voice registry use it): the same start and walk,
 * the tiers with their rates and the route — nothing reserved, no slot, no CDR, no machine, every step traced.
 */
class DryRunTest {

    private final Lab lab = new Lab();

    @AfterEach
    void close() { lab.close(); }

    @Test
    void aDryRun_walksTheTiersAndRoutes_reservingNothing_publishingNothing() {
        LabContext ctx = new LabContext();
        ctx.facts = new LabFacts("dry-1", 1L, Lab.BTCL_DOOR, 3, "8801");

        DryRun dry = lab.flow.simulate(ctx);

        assertThat(dry.admitted()).isTrue();
        assertThat(dry.levels()).extracting(l -> l.getDbName() + "/" + l.getPartnerId() + "/" + l.getRate().toPlainString())
            .containsExactly("res_44_7/3/1.00", "res_44/7/1.00", "btcl/44/1.00");
        assertThat(dry.plan().current()).isEqualTo(new LabHop("carrier-a"));
        assertThat(dry.trace()).anyMatch(r -> r.isNote() && r.cause().startsWith("RESERVE"));
        assertThat(lab.ledger.journal()).isEmpty();
        assertThat(lab.ledger.timesAsked("reserve")).isZero();
        assertThat(lab.kit.slots().held()).isZero();
        assertThat(lab.cdr.count()).isZero();
    }

    @Test
    void aDryRunRefused_saysTheCause() {
        LabContext unknown = new LabContext();
        unknown.facts = new LabFacts("dry-2", 1L, Lab.BTCL_DOOR, 999, "8801");
        LabContext nobody = new LabContext();
        nobody.facts = new LabFacts("dry-3", 1L, Lab.NOBODYS_DOOR, 1, "8801");

        assertThat(lab.flow.simulate(unknown).cause()).isEqualTo(SessionCause.PARTNER_NOT_FOUND);
        assertThat(lab.flow.simulate(nobody).cause()).isEqualTo(RoutesphereFlow.NO_TENANT);
        assertThat(lab.ledger.journal()).isEmpty();
    }
}
