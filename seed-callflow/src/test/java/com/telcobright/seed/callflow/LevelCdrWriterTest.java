package com.telcobright.seed.callflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.AdAdmission;
import com.telcobright.seed.callflow.api.AdCallPayload;
import com.telcobright.seed.callflow.api.AdCause;
import com.telcobright.seed.callflow.internal.ChainAdmission;
import com.telcobright.seed.callflow.internal.LevelCdrWriter;
import com.telcobright.seed.callflow.spi.TenantLookup;
import com.telcobright.seed.callflow.testkit.FakeBillingPort;
import com.telcobright.seed.callflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.pipeline.StepMode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The terminal write of design §2.5 on H2 in MySQL mode: a 2-tier success = 2 CDR rows + 1 task + 1 outbox row in ONE
 * commit; a failure mid-way rolls all of it back and never pings; a reject with zero tiers writes one row on the entry
 * tenant; the blob decodes as summary-service's decoder decodes it ({@code {Cdr, Chargeables:[…]}}, one leg per tier).
 */
class LevelCdrWriterTest {

    static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    JdbcDataSource ds;
    AtomicInteger pings;
    LevelCdrWriter writer;
    Tenant btcl;
    ChainAdmission chain;
    FakeBillingPort ledger;

    @BeforeEach
    void up() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cdr" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        pings = new AtomicInteger();
        writer = new LevelCdrWriter(ds, pings::incrementAndGet, DHAKA).ensureSchema().ensureSchema();
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(44, "res_44", PartnerType.RESELLER).partner(9, "network", PartnerType.CUSTOMER)
            .plan(20, "op", 44, 2).perView(20, "1001", "any", "0.40").and()
         .tenant("res_44", "btcl").partner(701, "Unilever", PartnerType.CUSTOMER).plan(10, "r1", 701, 2).perView(10, "1001", "any", "0.50");
        btcl = b.build();
        ledger = new FakeBillingPort().balance(701, "10").balance(44, "100");
        chain = new ChainAdmission(TenantLookup.of(btcl), ledger, Clock.systemUTC());
    }

    static AdCallPayload payload(String id, int payer) {
        long start = 1_790_700_000_000L;
        return new AdCallPayload(id, "AD", "btcl", "wifi", 1, "wifi-captive", "8801711111111", "1001", "8801711111111", "1001",
            1L, "1001", "dhaka", payer, 12, "lux-soap", 5, "lux-soap", "lux-1", payer, "video", "http://media.local/lux-1.mp4", 15,
            "dhaka-01", 9, 3, "dp-dhaka", "1001", 1, false, "dhaka-01", "site-7", "dhaka", "bras-1", "aa:bb:cc:dd:ee:01", "10.0.0.1", "8801711111111", "ios", "wifi-9",
            start, start + 1500, start + 17000, 15, true, true, AdCause.NORMAL_CLEARING.name(), List.of());
    }

    List<Map<String, Object>> rows(String sql) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (int i = 1; i <= n; i++) row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(), rs.getObject(i));
                out.add(row);
            }
        }
        return out;
    }

    static JsonNode decode(String data) throws Exception {
        byte[] gz = Base64.getDecoder().decode(data);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new ObjectMapper().readTree(in.readAllBytes());
        }
    }

    @Test
    void a_two_tier_success_is_two_rows_one_task_one_outbox_row_in_one_commit_then_the_ping() throws Exception {
        AdAdmission a = chain.admit(payload("ad-1", 701), "ad-1", StepMode.LIVE);
        assertThat(a.admitted()).isTrue();
        writer.writeAllLevels(payload("ad-1", 701), a.levels(), AdCause.NORMAL_CLEARING.name(), true);

        List<Map<String, Object>> cdrs = rows("SELECT * FROM ad_cdr ORDER BY levelIndex");
        assertThat(cdrs).hasSize(2);
        Map<String, Object> leaf = cdrs.get(0), root = cdrs.get(1);
        assertThat(leaf.get("tenant")).isEqualTo("res_44");
        assertThat(leaf.get("inpartnerid")).isEqualTo(701);
        assertThat(leaf.get("outpartnerid")).as("the network division").isEqualTo(9);
        assertThat(((Number) leaf.get("inpartnercost")).doubleValue()).isEqualTo(0.50);
        assertThat(leaf.get("inpartneruom")).isEqualTo("BDT");
        assertThat(leaf.get("matchprefixcustomer")).isEqualTo("1001");
        assertThat(leaf.get("hangupcause")).isEqualTo("NORMAL_CLEARING");
        assertThat(leaf.get("channelcalluuid")).isEqualTo("ad-1");
        assertThat(leaf.get("callid")).isEqualTo("5");
        assertThat(leaf.get("channelreadcodecname")).isEqualTo("video");
        assertThat(leaf.get("receiverip")).isEqualTo("media.local");
        assertThat(leaf.get("durationsec")).isEqualTo(15);
        assertThat(leaf.get("servicegroup")).isEqualTo(30);
        assertThat(leaf.get("resellerhierarchy")).isEqualTo("btcl > res_44");
        assertThat(((Number) leaf.get("balancebefore")).doubleValue()).isEqualTo(10.0);
        assertThat(((Number) leaf.get("balanceafter")).doubleValue()).isEqualTo(9.5);
        assertThat(((Number) leaf.get("pdd")).doubleValue()).isEqualTo(1.5);
        assertThat(leaf.get("zone")).as("ARCH-0001 §3.2: the report filters' columns").isEqualTo("dhaka-01");
        assertThat(leaf.get("app")).isEqualTo("wifi");
        assertThat(leaf.get("rulecode")).isEqualTo("1001");
        assertThat(leaf.get("fallback")).isEqualTo(0);
        assertThat(root.get("tenant")).isEqualTo("btcl");
        assertThat(root.get("inpartnerid")).isEqualTo(44);
        assertThat(((Number) root.get("inpartnercost")).doubleValue()).isEqualTo(0.40);
        assertThat(root.get("resellerhierarchy")).isEqualTo("btcl");
        assertThat(root.get("sequenceno")).isEqualTo(leaf.get("sequenceno"));

        List<Map<String, Object>> tasks = rows("SELECT * FROM campaign_task");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).get("uniqueid")).isEqualTo("ad-1");
        assertThat(tasks.get(0).get("state")).isEqualTo(11);
        assertThat(tasks.get(0).get("answered")).isEqualTo(1);
        assertThat(cdrs.get(0).get("credited")).as("ARCH-0001 5.5: the free session followed").isEqualTo(1);
        assertThat(tasks.get(0).get("billsec")).isEqualTo(15);
        assertThat(tasks.get(0).get("hangup_cause")).isEqualTo("NORMAL_CLEARING");
        assertThat(tasks.get(0).get("campaign_id")).isEqualTo(5);
        assertThat(tasks.get(0).get("id_partner")).isEqualTo(701);

        List<Map<String, Object>> outbox = rows("SELECT * FROM summary_affected");
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).get("entity_type")).isEqualTo("ad_cdr");
        assertThat(outbox.get(0).get("op")).isEqualTo("add");
        JsonNode batch = decode((String) outbox.get(0).get("data"));
        assertThat(batch.isArray()).isTrue();
        assertThat(batch).hasSize(1);
        JsonNode cdr = batch.get(0).get("Cdr");
        assertThat(cdr.get("InPartnerId").asInt()).isEqualTo(701);
        assertThat(cdr.get("OutPartnerId").asInt()).isEqualTo(9);
        assertThat(cdr.get("StartTime").asText()).isEqualTo("2026-09-29T22:40:00");     // 16:40Z in Dhaka
        assertThat(cdr.get("ConnectTime").asText()).isEqualTo("2026-09-29T22:40:01");
        assertThat(cdr.get("DurationSec").asInt()).isEqualTo(15);
        assertThat(cdr.get("MatchedPrefixCustomer").asText()).isEqualTo("1001");
        assertThat(cdr.get("Tenant").asText()).isEqualTo("btcl");
        assertThat(cdr.get("CampaignId").asInt()).isEqualTo(5);
        assertThat(cdr.get("RuleCode").asText()).isEqualTo("1001");
        assertThat(cdr.get("Zone").asText()).isEqualTo("dhaka-01");
        assertThat(cdr.get("Outcome").asText()).isEqualTo("done");
        JsonNode legs = batch.get(0).get("Chargeables");
        assertThat(legs).hasSize(2);
        assertThat(legs.get(0).get("Tenant").asText()).isEqualTo("res_44");
        assertThat(legs.get(0).get("PartnerId").asInt()).isEqualTo(701);
        assertThat(legs.get(0).get("BilledAmount").decimalValue()).isEqualByComparingTo("0.50");
        assertThat(legs.get(0).get("servicegroup").asInt()).isEqualTo(30);
        assertThat(legs.get(0).get("assignedDirection").asInt()).isEqualTo(1);
        assertThat(legs.get(0).get("transactionTime").asText()).isEqualTo("2026-09-29T22:40:00");
        assertThat(legs.get(1).get("Tenant").asText()).isEqualTo("btcl");
        assertThat(legs.get(1).get("PartnerId").asInt()).isEqualTo(44);
        assertThat(legs.get(1).get("BilledAmount").decimalValue()).isEqualByComparingTo("0.40");
        assertThat(pings.get()).isEqualTo(1);

        assertThat(rows("SELECT cdr_state FROM cdr_state WHERE id = 1").get(0).get("cdr_state")).isEqualTo(1_000_001L);
    }

    @Test
    void a_failure_mid_way_rolls_everything_back_and_never_pings() throws Exception {
        AdAdmission a = chain.admit(payload("ad-2", 701), "ad-2", StepMode.LIVE);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) { st.execute("DROP TABLE summary_affected"); }
        assertThatThrownBy(() -> writer.writeAllLevels(payload("ad-2", 701), a.levels(), AdCause.NORMAL_CLEARING.name(), true))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("ad-2");
        assertThat(rows("SELECT * FROM ad_cdr")).as("the two CDR rows were rolled back with the outbox failure").isEmpty();
        assertThat(rows("SELECT * FROM campaign_task")).isEmpty();
        assertThat(pings.get()).isZero();
    }

    @Test
    void a_reject_with_no_tier_writes_one_row_on_the_entry_tenant_with_the_advertiser_and_a_failed_task() throws Exception {
        AdCallPayload p = AdCallPayload.minimal("ad-3", "btcl", "wifi", "8801711111111", null, 1_790_700_000_000L)
            .withLifecycle(0, 1_790_700_000_400L, 0, false, AdCause.NO_RULE.name());
        writer.writeAllLevels(p, List.of(), AdCause.NO_RULE.name(), false);
        List<Map<String, Object>> cdrs = rows("SELECT * FROM ad_cdr");
        assertThat(cdrs).hasSize(1);
        assertThat(cdrs.get(0).get("tenant")).isEqualTo("btcl");
        assertThat(cdrs.get(0).get("hangupcause")).isEqualTo("NO_RULE");
        assertThat(cdrs.get(0).get("inpartnerid")).isNull();
        assertThat(cdrs.get(0).get("answered")).isEqualTo(0);
        assertThat(cdrs.get(0).get("answertime")).isNull();
        List<Map<String, Object>> tasks = rows("SELECT * FROM campaign_task");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).get("state")).isEqualTo(5);
        assertThat(tasks.get(0).get("campaign_id")).isEqualTo(0);
        JsonNode batch = decode((String) rows("SELECT data FROM summary_affected").get(0).get("data"));
        assertThat(batch.get(0).get("Chargeables")).isEmpty();
        assertThat(batch.get(0).get("Cdr").get("HangupCause").asText()).isEqualTo("NO_RULE");
        assertThat(batch.get(0).get("Cdr").get("ChargingStatus").asInt()).isZero();

        AdCallPayload known = payload("ad-4", 701).withLifecycle(0, 1_790_700_020_000L, 0, false, AdCause.NOT_SHOWN.name());
        writer.writeAllLevels(known, List.of(), AdCause.NOT_SHOWN.name(), false);
        assertThat(rows("SELECT inPartnerId FROM ad_cdr WHERE channelCallUuid = 'ad-4'").get(0).get("inpartnerid")).as("the advertiser when known").isEqualTo(701);
    }

    @Test
    void an_existing_claim_is_updated_not_duplicated_and_the_sequence_advances_per_call() throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO campaign_task (uniqueId, CAMPAIGN_ID, ID_PARTNER, PHONE_NUMBER, CREATED_STAMP, STATE, TASK_TYPE) VALUES ('ad-5', 5, 701, '8801711111111', NOW(), 1, 'AD')");
        }
        AdAdmission a = chain.admit(payload("ad-5", 701), "ad-5", StepMode.LIVE);
        writer.writeAllLevels(payload("ad-5", 701), a.levels(), AdCause.ABANDONED.name(), true);
        List<Map<String, Object>> tasks = rows("SELECT * FROM campaign_task");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).get("state")).as("abandoned = failed for good, the debit kept").isEqualTo(5);
        assertThat(((Number) tasks.get(0).get("inpartnercost")).doubleValue()).isEqualTo(0.50);
        assertThat(tasks.get(0).get("matchedprefixcustomer")).isEqualTo("1001");
        AdAdmission b = chain.admit(payload("ad-6", 701), "ad-6", StepMode.LIVE);
        writer.writeAllLevels(payload("ad-6", 701), b.levels(), AdCause.NORMAL_CLEARING.name(), true);
        List<Map<String, Object>> seqs = rows("SELECT DISTINCT sequenceNo FROM ad_cdr ORDER BY sequenceNo");
        assertThat(seqs).hasSize(2);
        assertThat(((Number) seqs.get(1).get("sequenceno")).longValue()).isEqualTo(((Number) seqs.get(0).get("sequenceno")).longValue() + 1);
        assertThat(rows("SELECT cdr_state FROM cdr_state").get(0).get("cdr_state")).isEqualTo(1_000_002L);
    }
}
