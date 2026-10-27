package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.Tier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L3 · {@code invertLegs}, the lane's pre-processing step AFTER pre-routing (the owner, 2026-10-27: "a pre-processing step that inverts the
 * customer and supplier leg after pre-routing"): the base asks it for EVERY candidate it tries, right before that candidate's payer is
 * found — so the ad can make each try's advertiser the paying leg. A lane that does not answer it (the call) is unchanged.
 */
class InvertLegsStepTest {

    record Payer(int partnerId) implements Candidate {}

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    @Test
    void everyCandidateTried_itsLegsInvertedRightBeforeItsPayerIsFound() {
        List<String> steps = new CopyOnWriteArrayList<>();
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of("res_44", "500.00"), Lab.hops("carrier-a"), Set.of()) {
            @Override protected List<Candidate> selectCandidates(LabContext ctx) { return List.of(new Payer(3), new Payer(5)); }
            @Override protected void invertLegs(LabContext ctx, Candidate candidate) { steps.add("invert:" + ((Payer) candidate).partnerId()); }
            @Override protected Tier identifyPartner(LabContext ctx, Candidate candidate) {
                steps.add("identify:" + ((Payer) candidate).partnerId());
                return byPartnerId(ctx, ((Payer) candidate).partnerId());
            }
        });
        lab.ledger.fund("res_44", 5, "1000.00");
        lab.ledger.fund("btcl", 44, "100.00");

        lab.dial("v-1", Lab.BTCL_DOOR, 0);

        assertThat(steps).as("the first try refused at its middle tier, the second admitted — each inverted first")
            .containsExactly("invert:3", "identify:3", "invert:5", "identify:5");
        assertThat(lab.ctx("v-1").chosen).isEqualTo(new Payer(5));
    }
}
