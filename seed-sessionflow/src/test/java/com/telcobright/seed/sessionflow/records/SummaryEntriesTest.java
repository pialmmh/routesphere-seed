package com.telcobright.seed.sessionflow.records;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.dependencies.RecordDialect;
import com.telcobright.seed.sessionflow.dependencies.RecordStore;
import com.telcobright.seed.sessionflow.internal.CdrTableSql;
import com.telcobright.seed.sessionflow.internal.IdBook;
import com.telcobright.seed.sessionflow.internal.StatementRunner;
import com.telcobright.seed.sessionflow.internal.SummaryBlob;
import com.telcobright.seed.sessionflow.internal.SummaryEntries;
import com.telcobright.seed.sessionflow.testkit.RecordTables;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §A · The base's default summary entry: one record in billing-core's blob shape ({@code SummaryOutboxWriter}'s {@code Entry}, blob v2) —
 * {@code {"Cdr", "Chargeables"}} by billing-core's names, a time as its mapper's array, the empty fields left out, the IdCall the record's
 * INSERT was given — and read back with summary-service's own steps.
 */
class SummaryEntriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theEntryIsBillingCoresShape_theCdrByItsNames_aTimeAsAnArray_thePreRatedLegOfAStatedGroup() throws Exception {
        CdrEvent view = Records.adView("v-1", "btcl > res_44", 701);
        JsonNode entry = JSON.readTree(SummaryServiceReader.decode(SummaryBlob.encode(List.of(SummaryEntries.of(view, 7)), null))).get(0);

        assertThat(entry.fieldNames()).toIterable().containsExactly("Cdr", "Chargeables");
        JsonNode cdr = entry.get("Cdr");
        assertThat(cdr.get("SwitchId").asInt()).isEqualTo(7);
        assertThat(cdr.get("ServiceGroup").asInt()).isEqualTo(30);
        assertThat(cdr.get("ChannelCallUuid").asText()).isEqualTo("v-1");
        assertThat(cdr.get("UniqueBillId").asText()).isEqualTo("v-1");
        assertThat(cdr.get("StartTime").toString()).as("billing-core's JavaTimeModule array").isEqualTo("[2026,10,23,17,10,5]");
        assertThat(cdr.get("AnswerTime").toString()).isEqualTo("[2026,10,23,17,10,6]");
        assertThat(cdr.has("SupplierRate")).as("an empty field is left out").isFalse();
        assertThat(cdr.get("IdCall").asLong()).as("no try gave it a number").isZero();
        JsonNode leg = entry.get("Chargeables").get(0);
        assertThat(leg.get("assignedDirection").asInt()).isEqualTo(1);
        assertThat(leg.get("servicefamily").asInt()).isEqualTo(30);
        assertThat(leg.get("BilledAmount").decimalValue()).isEqualByComparingTo("0.50");
        assertThat(leg.get("idBilledUom").asText()).isEqualTo("BDT");
        assertThat(leg.get("idQuantityUom").asText()).isEqualTo("TF_s");
        assertThat(leg.get("Quantity").decimalValue()).isEqualByComparingTo("15");
        assertThat(leg.get("transactionTime").toString()).isEqualTo("[2026,10,23,17,10,5]");
        assertThat(leg.has("TaxAmount1")).isFalse();
    }

    @Test
    void aTimeOnAWholeMinute_isWrittenWithoutItsSeconds_asBillingCoresMapperWritesIt() throws Exception {
        CdrEvent view = Records.adView("v-0", "btcl", 9);
        view.endTime = "2026-10-23 17:11:00";
        JsonNode cdr = JSON.readTree(SummaryServiceReader.decode(SummaryBlob.encode(List.of(SummaryEntries.of(view, 0)), null))).get(0).get("Cdr");
        assertThat(cdr.get("EndTime").toString()).isEqualTo("[2026,10,23,17,11]");
        assertThat(SummaryServiceReader.read(SummaryBlob.encode(List.of(SummaryEntries.of(view, 0)), null))).hasSize(1);
    }

    @Test
    void summaryServiceReadsIt_withItsOwnDecodeAndMapper_theViewTheChargeAndTheTimes() throws Exception {
        List<SummaryServiceReader.AdCallEntry> read = SummaryServiceReader.read(SummaryBlob.encode(List.of(SummaryEntries.of(Records.adView("v-1",
            "btcl > res_44", 701), 0)), null));

        assertThat(read).hasSize(1);
        SummaryServiceReader.AdCallCdr cdr = read.get(0).cdr();
        assertThat(cdr.serviceGroup()).isEqualTo(30);
        assertThat(cdr.inPartnerId()).isEqualTo(701);
        assertThat(cdr.originatingCalledNumber()).isEqualTo("R701");
        assertThat(cdr.codec()).isEqualTo("video");
        assertThat(cdr.hangupCause()).isEqualTo("NORMAL_CLEARING");
        assertThat(cdr.startTime()).isEqualTo(LocalDateTime.of(2026, 10, 23, 17, 10, 5));
        assertThat(cdr.answerTime()).isEqualTo(LocalDateTime.of(2026, 10, 23, 17, 10, 6));
        assertThat(cdr.durationSec()).isEqualByComparingTo("15");
        assertThat(cdr.additionalMetaData()).contains("\"campaignId\":71");
        SummaryServiceReader.Chargeable leg = read.get(0).customerLeg();
        assertThat(leg.billedAmount()).isEqualByComparingTo("0.50");
        assertThat(leg.idBilledUom()).isEqualTo("BDT");
        assertThat(leg.servicegroup()).isEqualTo(30);
        assertThat(leg.transactionTime()).isEqualTo(cdr.startTime());
    }

    @Test
    void aCallOfNoStatedGroup_carriesNoLeg_billingRatesACallTheSwitchCannot() throws Exception {
        JsonNode entry = JSON.readTree(SummaryServiceReader.decode(SummaryBlob.encode(List.of(SummaryEntries.of(Records.call("c-1", "btcl", 9), 0)), null))).get(0);
        assertThat(entry.get("Cdr").get("ServiceGroup").asInt()).isZero();
        assertThat(entry.get("Chargeables")).isEmpty();
        assertThat(entry.get("Cdr").has("Duration1")).as("no pre-rated stamp on a call").isFalse();
    }

    @Test
    void theEntryCarriesTheIdCallItsInsertWasGiven_andTheLegItsIdEvent() throws Exception {
        JdbcConnectionPool pool = JdbcConnectionPool.create(RecordTables.url("entry" + System.nanoTime(), RecordDialect.POSTGRESQL), "sa", "");
        try (Connection c = pool.getConnection()) {
            RecordTables.create(pool, RecordDialect.POSTGRESQL, false, "res_44");
            CdrEvent view = Records.adView("v-9", "btcl > res_44", 701);
            IdBook ids = new IdBook(c);
            StatementRunner.writes(c, CdrTableSql.statementsOf(view, new RecordStore("btcl", pool, RecordDialect.POSTGRESQL, 0)), ids);
            JsonNode entry = JSON.readTree(SummaryServiceReader.decode(SummaryBlob.encode(List.of(SummaryEntries.of(view, 0)), ids))).get(0);
            assertThat(entry.get("Cdr").get("IdCall").asLong()).isEqualTo(1L);
            assertThat(entry.get("Chargeables").get(0).get("idEvent").asLong()).isEqualTo(1L);
        } finally {
            pool.dispose();
        }
    }
}
