package com.telcobright.seed.sessionflow.base;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.TierRate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O4 on the new base (the WiFi's B17, kept from the 3.x base): a renewal the tier's account cannot fund moves the tier onto the NEXT
 * account the application names — the current series settled for everything it held and closed as a span, a fresh series opened on the
 * next account under {@code <sid>.2#L<tier>} with its first window held at once; the CDR writes one record per span.
 */
class RotationTest {

    private Lab lab;

    @AfterEach
    void close() { if (lab != null) lab.close(); }

    @Test
    void theNextAccountTakesOver_theFirstSpanIsSettledAndRecorded_theSecondRunsOnToTheEnd() throws Exception {
        lab = new Lab(Lab.settings(1), kit -> new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected TierRate rateNextWindow(LabContext ctx, LevelAdmission tier) { return TierRate.of(BigDecimal.ONE, BigDecimal.ONE, "BDT", "880"); }
            @Override protected Long nextAccount(LabContext ctx, LevelAdmission tier) { return Long.valueOf(77).equals(tier.getChargeAccountId()) ? null : 77L; }
        });
        lab.ledger.fund("btcl", 1, "2.00");                                             // the admission's unit and one window
        lab.ledger.fundAccount("btcl", 1, 77, "10.00");                                  // the next purchase
        lab.dial("o-1", Lab.BTCL_DOOR, 1);
        lab.answer("o-1");
        long until = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < until && lab.ctx("o-1").closedSpans.isEmpty()) Thread.sleep(50);
        Thread.sleep(300);
        lab.hangup("o-1");

        LabContext ctx = lab.ctx("o-1");
        assertThat(ctx.closedSpans).hasSize(1);
        assertThat(ctx.tiers).extracting(t -> t.debitReference + "@" + t.chargeAccountId).containsExactly("o-1.2#L0@77");
        assertThat(lab.ledger.balanceOf("btcl", 1)).as("the first purchase spent").isEqualByComparingTo("0.00");
        assertThat(lab.ledger.openReserves()).isZero();
        List<CdrEvent> records = lab.cdr.of("o-1").get(0).tiers();
        assertThat(records).extracting(r -> r.channelCallUuid).containsExactly("o-1.1", "o-1.2");
        assertThat(records.get(0).inPartnerCost).isEqualByComparingTo("2.00");
    }
}
