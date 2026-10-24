package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.seed.sessionflow.api.RoutesphereFlow;
import com.telcobright.seed.sessionflow.base.LabEvents.Dial;
import com.telcobright.statewalk.channel.Delivery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-L item 2 · the owner's rule ("if we fail to identify the root level tenant at the ingress points, the request shall be rejected and
 * logs shall be generated") covers the machine's own road too: a session whose facts carry no root, and whose lane's rule names none at
 * its start, is refused {@code NO_TENANT} as before AND said through the door's ONE sayer (the kit's) — the same window per door, the same
 * {@code refusedNoRoot}. An ingress that follows the two lines never launches a session without a root: nothing is counted twice.
 */
class NoTenantAtTheStartTest {

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    private static LabFacts at(String sessionId, com.telcobright.seed.sessionflow.api.Ingress door) {
        return new LabFacts(sessionId, System.currentTimeMillis(), door, 1, "8801711000000");
    }

    @Test
    void aSessionTheMachineRefusesNoTenant_isSaid_andCounted() {
        lab = new Lab(Lab.settings(0));

        lab.dial("m-1", Lab.NOBODYS_DOOR, 1);

        assertThat(lab.ctx("m-1").startRefusal).isEqualTo(RoutesphereFlow.NO_TENANT);
        assertThat(lab.engine.stats().refusedNoRoot()).isEqualTo(1);
        assertThat(lab.engine.saidAtTheDoor()).singleElement().asString().contains("no root at esl 10.10.197.9:8021 (session m-1)")
            .contains("no rule names this door — the session is refused NO_TENANT");
    }

    @Test
    void anIngressFollowingTheTwoLines_refusesAtTheDoor_launchesNothing_andCountsOnce() {
        lab = new Lab(Lab.settings(0));
        LabFacts facts = at("m-2", Lab.NOBODYS_DOOR);

        String root = lab.engine.identifyRootLevelTenant(facts);                                   // line 1
        if (root != null) lab.engine.launchStateMachine("m-2", Delivery.to(LabCall.TYPE, new Dial(facts))).join();   // the guard, then line 2

        assertThat(root).isNull();
        assertThat(lab.engine.stats().refusedNoRoot()).as("counted once, at the door").isEqualTo(1);
        assertThat(lab.engine.stats().launched()).as("nothing launched").isZero();
        assertThat(lab.contexts).as("no session was made").isEmpty();
        assertThat(lab.engine.saidAtTheDoor()).singleElement().asString().contains("the request is refused");
    }

    @Test
    void theDoorAndTheStart_shareOneWindowPerDoor() {
        lab = new Lab(Lab.settings(0));

        lab.engine.identifyRootLevelTenant(at("m-3", Lab.NOBODYS_DOOR));
        lab.dial("m-4", Lab.NOBODYS_DOOR, 1);

        assertThat(lab.engine.stats().refusedNoRoot()).isEqualTo(2);
        assertThat(lab.engine.saidAtTheDoor()).as("the second in the same window of the same door: counted, not said").hasSize(1);
    }

    @Test
    void aRuleThatThrowsAtTheStart_isSaidWithItsWords_andNoted() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected String identifyRootLevelTenant(RoutesphereFacts facts) { throw new IllegalStateException("the rules are not loaded"); }
        });

        lab.dial("m-5", Lab.BTCL_DOOR, 1);

        assertThat(lab.ctx("m-5").startRefusal).isEqualTo(RoutesphereFlow.NO_TENANT);
        assertThat(lab.ctx("m-5").history.snapshot()).anyMatch(r -> r.isNote() && r.cause().contains("identifyRootLevelTenant threw"));
        assertThat(lab.engine.stats().refusedNoRoot()).isEqualTo(1);
        assertThat(lab.engine.saidAtTheDoor()).singleElement().asString().contains("(session m-5)").contains("the rule failed")
            .contains("the rules are not loaded").contains("the session is refused NO_TENANT");
    }

    @Test
    void aSessionWhoseDoorARuleNames_isNeitherSaidNorCounted() {
        lab = new Lab(Lab.settings(0));
        lab.dial("m-6", Lab.BTCL_DOOR, 1);
        assertThat(lab.ctx("m-6").root).isEqualTo("btcl");
        assertThat(lab.engine.stats().refusedNoRoot()).isZero();
        assertThat(lab.engine.saidAtTheDoor()).isEmpty();
    }
}
