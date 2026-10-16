package com.telcobright.seed.sessionflow.api;

import com.telcobright.statewalk.persistence.PersistenceMode;
import com.telcobright.statewalk.persistence.PersistenceProvider;
import com.telcobright.statewalk.registry.StatemachineRegistry;
import com.telcobright.statewalk.registry.Supervisor;
import com.telcobright.statewalk.state.StateMap;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session base's statewalk pin is 3.3.0 (ARCH-0087 — the owner's "fix the statewalk lib" of 2026-10-16): {@code PersistenceMode}
 * exists only from 3.3.0, so this class does not compile against 3.2.0 — that is the pin's proof. Persistence stays OFF in the base:
 * {@code SessionFlowEngine.buildRegistry} sets none, and a registry built with {@code PersistenceMode.OFF} never opens its store (the
 * supplier here throws if it is ever asked). A lane turns persistence on by its own config key, never the base.
 */
class StatewalkPinTest {

    @Test
    void off_isTheWordAConfigKeyParses() {
        assertThat(PersistenceMode.parse("off")).isSameAs(PersistenceMode.OFF);
    }

    @Test
    void off_buildsARegistryWithoutEverOpeningAStore() {
        Supplier<PersistenceProvider> neverOpened = () -> {
            throw new AssertionError("a store must never be opened when persistence is off");
        };
        StatemachineRegistry<Object> registry = StatemachineRegistry.<Object>builder("statewalk-pin-proof")
            .supervisor("proof", ProofSupervisor::new, 1)
            .threads(1)
            .persistence(PersistenceMode.OFF, neverOpened)
            .build();
        try {
            assertThat(registry.getName()).isEqualTo("statewalk-pin-proof");
            assertThat(registry.activeIdCount()).isZero();
        } finally {
            registry.shutdown();
        }
    }

    /** The smallest supervisor a registry pools: one state of its own (IDLE is statewalk's, auto-injected), nothing to do. */
    static final class ProofSupervisor extends Supervisor<Object> {
        @Override
        protected StateMap defineStates() {
            return StateMap.builder().initialState("PROVING").state("PROVING").interim().timeoutStay(1, TimeUnit.MINUTES).build();
        }
    }
}
