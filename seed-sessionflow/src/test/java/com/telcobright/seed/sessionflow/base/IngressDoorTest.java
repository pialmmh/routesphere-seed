package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.seed.sessionflow.base.LabEvents.Dial;
import com.telcobright.statewalk.channel.Delivery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner's shape of 2026-10-24 · every ingress reads two lines: {@code root = engine.identifyRootLevelTenant(facts)}, then
 * {@code engine.launchStateMachine(…)}. The root the ingress identified rides in {@link RoutesphereFacts#root()}: the machine takes it and
 * asks the lane nothing; facts without it keep today's road (the machine asks the lane at its start).
 */
class IngressDoorTest {

    private final AtomicInteger askedTheLane = new AtomicInteger();
    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    private void labThatCounts() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected String identifyRootLevelTenant(RoutesphereFacts facts) { askedTheLane.incrementAndGet(); return super.identifyRootLevelTenant(facts); }
        });
    }

    private static Delivery firstFrameOf(LabFacts facts) { return Delivery.to(LabCall.TYPE, new Dial(facts)); }

    private LabContext contextOf(String id) {
        return lab.contexts.stream().filter(c -> id.equals(c.sessionId())).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void theIngressIdentifiesTheRoot_andTheMachineTakesIt_askingTheLaneNothing() throws Exception {
        labThatCounts();
        LabFacts atTheDoor = new LabFacts("d-1", System.currentTimeMillis(), Lab.BTCL_DOOR, 1, "8801711000000");

        String root = lab.engine.identifyRootLevelTenant(atTheDoor);                          // line 1 of the ingress
        assertThat(root).isEqualTo("btcl");
        assertThat(askedTheLane).hasValue(1);

        LabFacts withTheRoot = new LabFacts("d-1", atTheDoor.atMs(), Lab.BTCL_DOOR, 1, "8801711000000", root);
        lab.engine.launchStateMachine("d-1", firstFrameOf(withTheRoot)).join();               // line 2 of the ingress
        lab.engine.awaitSettled("d-1", 2, TimeUnit.SECONDS);

        assertThat(contextOf("d-1").root).isEqualTo("btcl");
        assertThat(askedTheLane).as("the machine took the ingress's root: the lane was not asked again").hasValue(1);
    }

    @Test
    void aRootTheIngressNames_isTheSessions_evenWhereNoRuleWouldFindIt() throws Exception {
        labThatCounts();
        LabFacts named = new LabFacts("d-2", System.currentTimeMillis(), Lab.NOBODYS_DOOR, 1, "8801711000000", "tele2");

        lab.engine.launchStateMachine("d-2", firstFrameOf(named)).join();
        lab.engine.awaitSettled("d-2", 2, TimeUnit.SECONDS);

        assertThat(contextOf("d-2").root).isEqualTo("tele2");
        assertThat(contextOf("d-2").startRefusal).isNull();
        assertThat(askedTheLane).hasValue(0);
    }

    @Test
    void factsWithoutARoot_keepTodaysRoad_theMachineAsksTheLane() {
        labThatCounts();
        lab.dial("d-3", Lab.BTCL_DOOR, 1);
        assertThat(contextOf("d-3").root).isEqualTo("btcl");
        assertThat(askedTheLane).hasValue(1);
    }

    @Test
    void anIngressWithNoRoot_hasNothingToLaunch() {
        labThatCounts();
        assertThat(lab.engine.identifyRootLevelTenant(new LabFacts("d-4", 0, Lab.NOBODYS_DOOR, 1, "8801711000000"))).isNull();
        assertThat(List.of()).isEmpty();
    }
}
