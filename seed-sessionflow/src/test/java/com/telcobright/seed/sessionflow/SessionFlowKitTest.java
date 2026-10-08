package com.telcobright.seed.sessionflow;

import com.telcobright.seed.sessionflow.api.SessionFlowTimings;
import com.telcobright.seed.sessionflow.dependencies.SessionFlowKit;
import com.telcobright.seed.sessionflow.dependencies.SessionFlowSettings;
import com.telcobright.seed.sessionflow.samples.Scene;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a host hands in is checked at start-up, by name: nothing has a silent fallback. */
class SessionFlowKitTest {

    private final Scene scene = new Scene();

    @Test
    void theKitRefusesToBuildWithoutItsLedgerItsSinkOrItsZone() {
        assertThatThrownBy(() -> SessionFlowKit.builder().tenants(TenantLookup.of(scene.root)).cdrSink(scene.cdrs).zone(Scene.DHAKA).build())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("ledger");
        assertThatThrownBy(() -> SessionFlowKit.builder().tenants(TenantLookup.of(scene.root)).ledger(scene.ledger).zone(Scene.DHAKA).build())
            .hasMessageContaining("cdrSink");
        assertThatThrownBy(() -> SessionFlowKit.builder().tenants(TenantLookup.of(scene.root)).ledger(scene.ledger).cdrSink(scene.cdrs).build())
            .hasMessageContaining("zone");
        assertThatThrownBy(() -> SessionFlowKit.builder().ledger(scene.ledger).cdrSink(scene.cdrs).zone(Scene.DHAKA).build())
            .hasMessageContaining("tenants");
    }

    @Test
    void aKillerShorterThanTheLongestHealthyCallIsRefused_byName() {
        SessionFlowTimings timings = new SessionFlowTimings(3, 5, 30, 90, 3600, 10);

        assertThatThrownBy(() -> new SessionFlowSettings(100, 2, 3600, timings, 0, 60, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("globalTimeoutSec")
            .hasMessageContaining("3738");
        assertThat(new SessionFlowSettings(100, 2, 3739, timings, 0, 60, false).globalTimeoutSec()).isEqualTo(3739);
    }

    @Test
    void theDefaultsAreTheCallSwitchsOwn() {
        SessionFlowSettings defaults = SessionFlowSettings.defaults();

        assertThat(defaults.pool()).isEqualTo(1000);
        assertThat(defaults.timings()).as("120 s before the answer: the switch's 30 s to the first progress + 90 s of ringing, in one window")
            .isEqualTo(new SessionFlowTimings(3, 5, 120, 90, 3600, 10));
        assertThat(defaults.reservePeriodSec()).isZero();
        assertThat(defaults.withPool(50).withDebug(true).pool()).isEqualTo(50);
        assertThat(new SessionFlowTimings(3, 5, 30, 0, 3600, 10).hasRingingPhase()).isFalse();
    }
}
