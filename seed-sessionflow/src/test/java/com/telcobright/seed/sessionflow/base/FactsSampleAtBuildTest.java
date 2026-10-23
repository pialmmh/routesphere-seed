package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.registry.MachineSpec;
import com.telcobright.statewalk.session.SessionFacts;
import com.telcobright.statewalk.session.events.SessionStart;
import com.telcobright.statewalk.state.StateMap;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-E item 3 · the facts' sample refused at build (R2): statewalk's SessionStart carries any facts, so the compiler cannot force a lane's
 * facts to name their ingress — the engine refuses, at start, a signaling machine that shows no SessionStart sample of RoutesphereFacts
 * (statewalk 4.1's {@code MachineSpec.Builder.upSample}). Every spec of the seed shows its sample.
 */
class FactsSampleAtBuildTest {

    record PlainFacts(String sessionId, long atMs) implements SessionFacts {}

    private static MachineSpec<Object> bare(StatemachineEvent... samples) {
        MachineSpec.Builder<Object> b = MachineSpec.builder().name("Bare").contextFactory(Object::new)
            .stateMap(StateMap.builder().initialState(StateMap.IDLE)
                .state(StateMap.IDLE).timeout(0).on(LabEvents.Dial.class, "DONE")
                .state("DONE").finalState().timeout(1, SECONDS, "DONE")
                .build());
        for (StatemachineEvent s : samples) b.upSample(s);
        return b.build();
    }

    private static RoutesphereEngine.Builder<LabContext> engineWith(MachineSpec<?> signaling) {
        Lab lab = new Lab();
        lab.close();
        return RoutesphereEngine.of(new LabFlow(lab.kit, Map.of(), Lab.hops("carrier-a"), Set.of()), LabContext::new)
            .signaling(signaling).channel(new TestChannel<String, StatemachineEvent>("lab"), Lab::decode);
    }

    @Test
    void aSignalingMachineThatShowsNoSessionStart_isRefusedAtStart_inWords() {
        assertThatThrownBy(() -> engineWith(bare()).start())
            .hasMessageContaining("[LabSupervisor] the signaling machine 'Bare' shows no SessionStart sample")
            .hasMessageContaining(".upSample(new SessionStart(")
            .hasMessageContaining("NO_TENANT");
    }

    @Test
    void aSampleWhoseFactsAreNotRoutesphereFacts_isRefusedAtStart_namingTheirClass() {
        assertThatThrownBy(() -> engineWith(bare(new SessionStart(new PlainFacts("s", 0)))).start())
            .hasMessageContaining("its SessionStart sample carries " + PlainFacts.class.getName() + ", not RoutesphereFacts");
    }

    @Test
    void theLabsSignalingMachine_showsItsSample_andTheLabStarts() {
        assertThat(LabCall.spec().upSamples()).anySatisfy(s -> {
            assertThat(s).isInstanceOf(SessionStart.class);
            assertThat(((SessionStart) s).facts()).isInstanceOf(RoutesphereFacts.class);
            assertThat(((RoutesphereFacts) ((SessionStart) s).facts()).ingress()).isEqualTo(Ingress.esl("10.10.199.9", 8021));
        });
        try (Lab lab = new Lab()) {
            assertThat(lab.engine.registry()).isNotNull();
        }
    }
}
