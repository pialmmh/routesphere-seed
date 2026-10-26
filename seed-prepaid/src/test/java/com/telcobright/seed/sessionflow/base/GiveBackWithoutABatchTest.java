package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.SessionCause;
import com.telcobright.seed.sessionflow.api.Tier;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.ManualClock;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.statewalk.session.Verdict;
import com.telcobright.statewalk.session.events.SessionStart;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2b gate (ad-sphere AdCallStoryTest item2b, AdReturnRoadStoryTest three tiers): on a ledger that cannot write a batch (the testkit's, an
 * application's own road) the walk's holds are applied one by one — so a tier that cannot pay gives the earlier tiers their money back
 * exactly as before L2b: IN THE ORDER HELD (the leaf first), each release carrying the REFUSAL's cause.
 */
class GiveBackWithoutABatchTest {

    record Payer(int partnerId) implements Candidate {}

    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-27T00:00:00Z"), ZoneId.of("Asia/Dhaka"));
    private final InMemoryLedger ledger = new InMemoryLedger()
        .fund("res_44_7", 3, "100.00").fund("res_44", 7, "100.00").fund("btcl", 44, "0.00");        // the top tier cannot pay

    @Test
    void theTopTierCannotPay_theLowerTiersGetTheirMoneyBack_leafFirst_withTheRefusalsCause() {
        LabContext ctx = new LabContext();

        Verdict verdict = admit(ctx);

        assertThat(verdict.cause()).isEqualTo(SessionCause.INSUFFICIENT_BALANCE);
        List<InMemoryLedger.Entry> releases = ledger.journal().stream().filter(e -> e.verb().equals("release")).toList();
        assertThat(releases).extracting(InMemoryLedger.Entry::tenant).as("the leaf's first").containsExactly("res_44_7", "res_44");
        assertThat(releases).extracting(InMemoryLedger.Entry::note).containsOnly(SessionCause.INSUFFICIENT_BALANCE);
        assertThat(ledger.balanceOf("res_44_7", 3)).isEqualByComparingTo("100.00");
        assertThat(ledger.balanceOf("res_44", 7)).isEqualByComparingTo("100.00");
        assertThat(ledger.openReserves()).isZero();
    }

    private Verdict admit(LabContext ctx) {
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(Lab.btclTree())).identification(List.of(Lab.RULES.get(0)))
            .ledger(ledger).cdr(new RecordingCdrSink()).zone(ZoneId.of("Asia/Dhaka")).clock(clock).settings(Lab.settings(0)).build();
        LabFlow flow = new LabFlow(kit, Map.of(), Lab.hops("carrier-a"), Set.of()) {
            @Override protected List<Candidate> selectCandidates(LabContext c) { return List.of(new Payer(3)); }
            @Override protected Tier identifyPartner(LabContext c, Candidate candidate) { return byPartnerId(c, ((Payer) candidate).partnerId()); }
        };
        ctx.facts = new LabFacts("g-" + System.nanoTime(), clock.millis(), Lab.BTCL_DOOR, 0, "8801");
        flow.onStart(ctx, new SessionStart(ctx.facts));
        ctx.candidates = List.of(new Payer(3));
        return flow.admit(ctx);
    }
}
