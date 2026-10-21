package com.telcobright.seed.sessionflow.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.seed.sessionflow.api.Candidate;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.ManualClock;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.statewalk.session.Outcome;
import com.telcobright.statewalk.session.events.Established;
import com.telcobright.statewalk.session.events.SessionStart;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §G · The CDR as it goes on the wire (the ratified contract, routesphere docs/architecture/ad-is-a-call.md §4), on the new base: the
 * JSON a consumer reads — one array per session, the leaf first, the contract's names, the root zone's wall clock — kept from the 3.x
 * base's CdrWireTest. Driven through the handler on a manual clock, so the times are exact.
 */
class CdrWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

    private final RecordingCdrSink cdrs = new RecordingCdrSink();
    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-03T04:00:00Z"), DHAKA);    // 10:00:00 in Dhaka

    private JsonNode wireOfATwoTierSession(boolean answered) throws Exception {
        InMemoryLedger ledger = new InMemoryLedger().fund("res_44", 5, "100.00").fund("btcl", 44, "100.00");
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(Lab.btclTree())).identification(List.of(Lab.RULES.get(0)))
            .ledger(ledger).cdr(cdrs).zone(DHAKA).clock(clock).settings(Lab.settings(0)).build();
        LabFlow flow = new LabFlow(kit, Map.of("res_44", "0.50", "btcl", "0.40"), Lab.hops("carrier-a"), Set.of()) {
            @Override protected int serviceGroup(LabContext ctx) { return 30; }
            @Override protected void fillCdr(LabContext ctx, LevelAdmission tier, CdrEvent cdr) { cdr.meta.put("zone", "dhaka-zone"); }
        };
        LabContext ctx = new LabContext();
        ctx.facts = new LabFacts("wire-1", clock.millis(), Lab.BTCL_DOOR, 5, "8801711000000");
        flow.onStart(ctx, new SessionStart(ctx.facts));
        ctx.candidates = List.of(Candidate.SELF);
        assertThat(flow.admit(ctx).accepted()).isTrue();
        if (answered) ctx.answer = new Established(Instant.parse("2026-10-03T04:00:02Z").toEpochMilli(), "carrier-a", null, null);
        ctx.endCause = answered ? "NORMAL_CLEARING" : "NO_ANSWER";
        ctx.endedAtMs = Instant.parse("2026-10-03T04:00:17Z").toEpochMilli();
        if (answered) flow.settle(ctx, Outcome.SUCCEEDED); else flow.release(ctx);
        flow.record(ctx, answered ? Outcome.SUCCEEDED : Outcome.FAILED);
        return JSON.readTree(cdrs.of("wire-1").get(0).json());
    }

    @Test
    void oneSessionIsOneArray_theLeafFirst_withTheContractsNames() throws Exception {
        JsonNode call = wireOfATwoTierSession(true);

        assertThat(call.isArray()).isTrue();
        assertThat(call).hasSize(2);
        JsonNode leaf = call.get(0), top = call.get(1);
        assertThat(leaf.get("tenant").asText()).isEqualTo("res_44");
        assertThat(leaf.get("resellerHierarchy").asText()).isEqualTo("btcl > res_44");
        assertThat(top.get("tenant").asText()).isEqualTo("btcl");
        assertThat(top.get("resellerHierarchy").asText()).isEqualTo("btcl");
        assertThat(leaf.get("callId").asText()).isEqualTo("wire-1");
        assertThat(leaf.get("channelCallUuid").asText()).isEqualTo("wire-1");
        assertThat(leaf.get("serviceGroup").asInt()).isEqualTo(30);
        assertThat(leaf.get("inPartnerId").asInt()).isEqualTo(5);
        assertThat(top.get("inPartnerId").asInt()).isEqualTo(44);
        assertThat(leaf.get("isPrepaid").asInt()).isEqualTo(1);
        assertThat(leaf.get("matchPrefixCustomer").asText()).isEqualTo("880");
        assertThat(leaf.get("callRatePerMinBDT").decimalValue()).isEqualByComparingTo("0.50");
        assertThat(leaf.get("inPartnerUom").asText()).isEqualTo("BDT");
        assertThat(leaf.get("inPartnerCost").decimalValue()).isEqualByComparingTo("0.50");
        assertThat(leaf.get("packageAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(top.get("inPartnerCost").decimalValue()).isEqualByComparingTo("0.40");
        assertThat(leaf.get("hangupCause").asText()).isEqualTo("NORMAL_CLEARING");
        assertThat(leaf.get("durationSec").decimalValue()).isEqualByComparingTo("15");
        assertThat(leaf.get("sequenceNo").asLong()).isLessThan(top.get("sequenceNo").asLong());
    }

    @Test
    void theTimesAreTheRootZonesWallClock() throws Exception {
        JsonNode leaf = wireOfATwoTierSession(true).get(0);

        assertThat(leaf.get("startTime").asText()).isEqualTo("2026-10-03 10:00:00");
        assertThat(leaf.get("answerTime").asText()).isEqualTo("2026-10-03 10:00:02");
        assertThat(leaf.get("endTime").asText()).isEqualTo("2026-10-03 10:00:17");
        assertThat(leaf.get("pdd").floatValue()).isEqualTo(2.0f);
    }

    @Test
    void aSessionNeverAnsweredSendsItsAnswerTimeAsNull_theWorkingMapNeverOnTheWire_theFactsAsOneString() throws Exception {
        JsonNode leaf = wireOfATwoTierSession(false).get(0);

        assertThat(leaf.has("answerTime")).isTrue();
        assertThat(leaf.get("answerTime").isNull()).isTrue();
        assertThat(leaf.has("meta")).as("the working map never goes on the wire").isFalse();
        assertThat(leaf.get("hangupCause").asText()).isEqualTo("NO_ANSWER");
        assertThat(leaf.get("inPartnerCost").decimalValue()).isEqualByComparingTo("0");
        assertThat(JSON.readTree(leaf.get("additionalMetaData").asText()).get("zone").asText()).isEqualTo("dhaka-zone");
    }
}
