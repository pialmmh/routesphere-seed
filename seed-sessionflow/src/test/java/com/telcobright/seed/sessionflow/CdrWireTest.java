package com.telcobright.seed.sessionflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.sessionflow.api.SessionState;
import com.telcobright.seed.sessionflow.dependencies.CdrSinks;
import com.telcobright.seed.sessionflow.samples.AdFlow;
import com.telcobright.seed.sessionflow.samples.Scene;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.statewalk.pipeline.StepMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CDR as it goes on the wire, checked against the ratified contract (routesphere docs/architecture/ad-is-a-call.md
 * §4): the JSON a consumer reads, not the Java object.
 */
class CdrWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Scene scene = new Scene();
    private final AdFlow ad = scene.ad(Scene.settings(2), true);

    /** One two-tier ad view, admitted, shown at a known instant and ended — with no machine, so the times are exact. */
    private JsonNode wireOfATwoTierView(boolean shown) throws Exception {
        AdFlow.View view = Scene.view("ad-wire-1", "dhaka-zone");
        view.createdAtMs = Instant.parse("2026-10-03T04:00:00Z").toEpochMilli();          // 10:00:00 in Dhaka
        assertThat(ad.preprocess(view)).isNull();
        assertThat(ad.admission(view, StepMode.LIVE).accepted()).isTrue();
        if (shown) view.answeredAtMs = Instant.parse("2026-10-03T04:00:02Z").toEpochMilli();
        view.durationSec = shown ? 15 : 0;
        view.endCause = shown ? "NORMAL_CLEARING" : "NOT_SHOWN";
        view.endedAtMs = Instant.parse("2026-10-03T04:00:17Z").toEpochMilli();
        ad.close(view, shown ? SessionState.SUCCEEDED : SessionState.FAILED, Scene.NO_MACHINE);
        return JSON.readTree(scene.cdrs.of("ad-wire-1").get(0).json());
    }

    @Test
    void oneCallIsOneArray_theLeafFirst_withTheContractsNames() throws Exception {
        JsonNode call = wireOfATwoTierView(true);

        assertThat(call.isArray()).isTrue();
        assertThat(call).hasSize(2);
        JsonNode leaf = call.get(0), top = call.get(1);
        assertThat(leaf.get("tenant").asText()).isEqualTo("res_44");
        assertThat(leaf.get("resellerHierarchy").asText()).isEqualTo("btcl > res_44");
        assertThat(top.get("tenant").asText()).isEqualTo("btcl");
        assertThat(top.get("resellerHierarchy").asText()).isEqualTo("btcl");
        assertThat(leaf.get("callId").asText()).isEqualTo("ad-wire-1");
        assertThat(leaf.get("channelCallUuid").asText()).isEqualTo("ad-wire-1");
        assertThat(leaf.get("serviceGroup").asInt()).isEqualTo(30);
        assertThat(leaf.get("inPartnerId").asInt()).isEqualTo(701);
        assertThat(top.get("inPartnerId").asInt()).isEqualTo(44);
        assertThat(leaf.get("isPrepaid").asInt()).isEqualTo(1);
        assertThat(leaf.get("incomingRoute").asText()).isEqualTo("camp-11");
        assertThat(leaf.get("outgoingRoute").asText()).isEqualTo("dhaka-zone");
        assertThat(leaf.get("matchPrefixCustomer").asText()).isEqualTo("R100");
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
    void theTimesAreTheRootZonesWallClock_asTheCsvWritesThem() throws Exception {
        JsonNode leaf = wireOfATwoTierView(true).get(0);

        assertThat(leaf.get("startTime").asText()).isEqualTo("2026-10-03 10:00:00");
        assertThat(leaf.get("answerTime").asText()).isEqualTo("2026-10-03 10:00:02");
        assertThat(leaf.get("endTime").asText()).isEqualTo("2026-10-03 10:00:17");
        assertThat(leaf.get("pdd").floatValue()).isEqualTo(2.0f);
    }

    @Test
    void aCallNeverAnsweredSendsItsAnswerTimeAsNull_andEveryOtherUnknownFieldIsLeftOut() throws Exception {
        JsonNode leaf = wireOfATwoTierView(false).get(0);

        assertThat(leaf.has("answerTime")).as("the field is sent").isTrue();
        assertThat(leaf.get("answerTime").isNull()).isTrue();
        assertThat(leaf.has("receiverIp")).isFalse();
        assertThat(leaf.has("supplierCost")).isFalse();
        assertThat(leaf.has("meta")).as("the working map never goes on the wire").isFalse();
        assertThat(leaf.get("hangupCause").asText()).isEqualTo("NOT_SHOWN");
    }

    @Test
    void theApplicationsFactsTravelAsOneJsonObjectInAString() throws Exception {
        JsonNode leaf = wireOfATwoTierView(true).get(0);

        assertThat(leaf.get("additionalMetaData").isTextual()).isTrue();
        JsonNode meta = JSON.readTree(leaf.get("additionalMetaData").asText());
        assertThat(meta.get("campaignId").asInt()).isEqualTo(11);
        assertThat(meta.get("zone").asText()).isEqualTo("dhaka-zone");
        assertThat(meta.get("levelIndex").asInt()).isZero();
        assertThat(meta.get("reserveRef").asText()).isEqualTo("ad-wire-1#2#L0");
        assertThat(meta.get("balanceBefore").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(meta.get("balanceAfter").decimalValue()).isEqualByComparingTo("99.50");
    }

    @Test
    void theJournalKeepsEveryCallOfAMinute_andAFileCanBeSentAgain(@TempDir Path dir) throws Exception {
        Clock tenOClock = Clock.fixed(Instant.parse("2026-10-03T04:00:30Z"), Scene.DHAKA);
        RecordingCdrSink kafka = new RecordingCdrSink();
        try (CdrSink both = CdrSinks.all(CdrSinks.journal(dir, tenOClock, Scene.DHAKA), kafka)) {
            wireOfATwoTierView(true);
            both.publish("ad-wire-1", scene.cdrs.of("ad-wire-1").get(0).tiers());
            both.publish("ad-wire-2", scene.cdrs.of("ad-wire-1").get(0).tiers());
        }
        Path minuteFile = dir.resolve("cdr-20261003-1000.jsonl");

        assertThat(minuteFile).exists();
        List<String> lines = Files.readAllLines(minuteFile);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith("ad-wire-1\t[{");
        assertThat(lines.get(0).substring(lines.get(0).indexOf('\t') + 1)).as("a line is exactly the Kafka value").isEqualTo(kafka.published().get(0).json());

        RecordingCdrSink again = new RecordingCdrSink();
        int sent = CdrSinks.replay(minuteFile, again);

        assertThat(sent).isEqualTo(2);
        assertThat(again.published()).extracting(RecordingCdrSink.Published::callId).containsExactly("ad-wire-1", "ad-wire-2");
        assertThat(again.published().get(0).json()).isEqualTo(kafka.published().get(0).json());
    }
}
