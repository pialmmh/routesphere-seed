package com.telcobright.seed.sessionflow.base;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-E item 9 · the prepaid piece (R12) — ONE reserve and ONE finalize in the base, no lane writes money code: admission reserves each
 * tier's rate (one unit); the finish finalizes — delivered: charged rate × billed units, the rest of the reserve back. The call's case.
 */
class PrepaidPieceTest {

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    @Test
    void anAnsweredCall_paysRateTimesItsUnits_andTheRestOfTheReserveComesBack() {
        lab = new Lab(Lab.settings(0), kit -> new LabFlow(kit, Map.of("btcl", "1.00"), Lab.hops("carrier-a"), Set.of()) {
            @Override protected BigDecimal billedUnits(LabContext ctx) { return new BigDecimal("0.25"); }   // a quarter of the unit reserved
        });
        lab.dial("p-1", Lab.BTCL_DOOR, 1);
        assertThat(lab.ledger.balanceOf("btcl", 1)).as("reserved: the tier's rate, one unit").isEqualByComparingTo("99.00");
        lab.hangupWhenAnswered("p-1");

        assertThat(lab.ledger.balanceOf("btcl", 1)).as("charged 1.00 × 0.25; the other 0.75 back").isEqualByComparingTo("99.75");
        assertThat(lab.ctx("p-1").settlements.get(0).charged()).isEqualByComparingTo("0.25");
        assertThat(lab.ledger.openReserves()).isZero();
    }
}
