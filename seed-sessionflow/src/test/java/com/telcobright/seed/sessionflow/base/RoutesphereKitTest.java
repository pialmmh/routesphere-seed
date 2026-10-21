package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.statewalk.session.SessionTimings;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The kit (kept from the 3.x SessionFlowKitTest): no fallback for the trees, the ledger, the CDR sink or the zone; the settings' guards. */
class RoutesphereKitTest {

    private static RoutesphereKit.Builder complete() {
        return RoutesphereKit.builder().tenants(TenantLookup.of(Lab.btclTree())).ledger(new InMemoryLedger()).cdr((id, t) -> { }).zone(ZoneId.of("Asia/Dhaka"));
    }

    @Test
    void theTreesTheLedgerTheCdrSinkAndTheZone_haveNoDefault() {
        assertThatThrownBy(() -> complete().tenants(null).build()).hasMessageContaining("tenants");
        assertThatThrownBy(() -> complete().ledger(null).build()).hasMessageContaining("ledger");
        assertThatThrownBy(() -> complete().cdr(null).build()).hasMessageContaining("cdr");
        assertThatThrownBy(() -> complete().zone(null).build()).hasMessageContaining("zone");
    }

    @Test
    void withoutRules_everyIngressIsNobodys_neverADefaultRoot() {
        RoutesphereKit kit = complete().build();
        assertThat(kit.resolver().tenantOf(Ingress.esl("10.10.199.9", 8021))).isEmpty();
        assertThat(kit.resolver().tenantOf(null)).isEmpty();
        assertThat(kit.sessionOf("nobody")).as("no engine attached yet").isEmpty();
    }

    @Test
    void theSettingsRefuseALifetimeCapShorterThanAHealthySession_andAReserveLongerThanAdmission() {
        assertThatThrownBy(() -> new RoutesphereSettings(8, 2, 30, new SessionTimings(2, 2, 5, 60), 0, 0, 0, false, 200))
            .hasMessageContaining("longest healthy session");
        assertThatThrownBy(() -> new RoutesphereSettings(8, 2, 120, new SessionTimings(2, 2, 5, 60), 0, 0, 0, false, 2000))
            .hasMessageContaining("admissionReserveMs");
        assertThat(Lab.settings(60).firstRenewalSec()).isEqualTo(60);
        assertThat(Lab.settings(60).withReserveInitialDelaySec(58).firstRenewalSec()).isEqualTo(58);
        assertThat(Lab.settings(0).admissionBudgetMs()).isEqualTo(1800);
    }
}
