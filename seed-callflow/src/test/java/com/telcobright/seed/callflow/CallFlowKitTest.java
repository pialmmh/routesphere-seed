package com.telcobright.seed.callflow;

import com.telcobright.seed.callflow.api.CallFlowTimings;
import com.telcobright.seed.callflow.dependencies.CallFlowKit;
import com.telcobright.seed.callflow.dependencies.CallFlowSettings;
import com.telcobright.seed.callflow.samples.Scene;
import com.telcobright.seed.callflow.spi.TenantLookup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a host hands in is checked at start-up, by name: nothing has a silent fallback. */
class CallFlowKitTest {

    private final Scene scene = new Scene();

    @Test
    void theKitRefusesToBuildWithoutItsLedgerItsSinkOrItsZone() {
        assertThatThrownBy(() -> CallFlowKit.builder().tenants(TenantLookup.of(scene.root)).cdrSink(scene.cdrs).zone(Scene.DHAKA).build())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("ledger");
        assertThatThrownBy(() -> CallFlowKit.builder().tenants(TenantLookup.of(scene.root)).ledger(scene.ledger).zone(Scene.DHAKA).build())
            .hasMessageContaining("cdrSink");
        assertThatThrownBy(() -> CallFlowKit.builder().tenants(TenantLookup.of(scene.root)).ledger(scene.ledger).cdrSink(scene.cdrs).build())
            .hasMessageContaining("zone");
        assertThatThrownBy(() -> CallFlowKit.builder().ledger(scene.ledger).cdrSink(scene.cdrs).zone(Scene.DHAKA).build())
            .hasMessageContaining("tenants");
    }

    @Test
    void aKillerShorterThanTheLongestHealthyCallIsRefused_byName() {
        CallFlowTimings timings = new CallFlowTimings(3, 5, 30, 90, 3600, 10);

        assertThatThrownBy(() -> new CallFlowSettings(100, 2, 3600, timings, 0, 60, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("globalTimeoutSec")
            .hasMessageContaining("3738");
        assertThat(new CallFlowSettings(100, 2, 3739, timings, 0, 60, false).globalTimeoutSec()).isEqualTo(3739);
    }

    @Test
    void theDefaultsAreTheCallSwitchsOwn() {
        CallFlowSettings defaults = CallFlowSettings.defaults();

        assertThat(defaults.pool()).isEqualTo(1000);
        assertThat(defaults.timings()).isEqualTo(new CallFlowTimings(3, 5, 30, 90, 3600, 10));
        assertThat(defaults.reservePeriodSec()).isZero();
        assertThat(defaults.withPool(50).withDebug(true).pool()).isEqualTo(50);
        assertThat(new CallFlowTimings(3, 5, 30, 0, 3600, 10).hasRingingPhase()).isFalse();
    }
}
