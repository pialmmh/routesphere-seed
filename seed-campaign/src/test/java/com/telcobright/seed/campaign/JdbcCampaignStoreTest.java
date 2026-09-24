package com.telcobright.seed.campaign;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.MediaKind;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.seed.campaign.jdbc.CampaignSchema;
import com.telcobright.seed.campaign.jdbc.JdbcCampaignStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** routesphere's schema, H2 in MySQL mode: a campaign written the UI's way comes back whole; a task's life lands in its row. */
class JdbcCampaignStoreTest {

    JdbcDataSource ds;
    JdbcCampaignStore store;

    @BeforeEach
    void schema() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            CampaignSchema.createAll(c);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO policy (id, name) VALUES (7, 'daytime')");
                st.execute("INSERT INTO time_band (allow_or_restrict, `day`, start_time, end_time, policy_id) VALUES (1, 'WEEKDAYS ONLY', '09:00:00', '17:00:00', 7)");
                st.execute("INSERT INTO schedule_policy (ID, START_TIME, END_TIME, NAME) VALUES (3, '2026-09-01 00:00:00', '2026-12-31 23:59:59', 'q4')");
                st.execute("INSERT INTO campaign (CAMPAIGN_ID, CAMPAIGN_NAME, CAMPAIGN_TYPE, STATUS, ID_PARTNER, PRIORITY, TOTAL_TASK_COUNT, SENT_TASK_COUNT, FAILED_TASK_COUNT, PENDING_TASK_COUNT, POLICY_ID, SCHEDULE_POLICY_ID, MESSAGE, FIELD1, FIELD2, EXTERNAL_CAMPAIGN_ID) "
                    + "VALUES (42, 'Cola Q4', 'AD', 10, 701, 5, 10000, 12, 3, 0, 7, 3, 'Drink it', 2, 15, 'ext-42')");
                st.execute("INSERT INTO campaign (CAMPAIGN_ID, CAMPAIGN_NAME, CAMPAIGN_TYPE, STATUS, ID_PARTNER, EXTERNAL_CAMPAIGN_ID, AUDIO_FILE_PATH, MESSAGE) VALUES (43, 'voice-style', 'AD', 1, 0, 'ext-43', 'vod-legacy', 'old way')");
                st.execute("INSERT INTO campaign (CAMPAIGN_ID, CAMPAIGN_NAME, CAMPAIGN_TYPE, STATUS, ID_PARTNER, EXTERNAL_CAMPAIGN_ID) VALUES (44, 'an sms one', 'SMS', 10, 701, 'ext-44')");
                st.execute("INSERT INTO campaign_target (campaign_id, dimension, `value`) VALUES (42, 'zone', 'zone0'), (42, 'zone', 'zone1'), (42, 'district', 'dhaka')");
                st.execute("INSERT INTO campaign_creative (campaign_id, creative_id, kind, media_ref, duration_sec, click_url, caption, active) VALUES (42, 'c-77', 'VIDEO', 'vod-77', 15, 'https://cola.example', 'Cola', 1), (42, 'c-78', 'IMAGE', 'vod-78', 0, NULL, NULL, 1), (42, 'c-79', 'IMAGE', 'vod-79', 0, NULL, NULL, 0)");
            }
        }
        store = new JdbcCampaignStore(ds, CampaignKind.AD, JdbcCampaignStoreTest::json);
    }

    @Test
    void the_ad_campaigns_come_back_with_status_window_bands_targets_and_creatives() {
        List<Campaign> all = store.campaigns("btcl");

        assertThat(all).extracting(Campaign::id).as("only this store's kind").containsExactly(42, 43);
        Campaign c = all.get(0);
        assertThat(c.tenantId()).isEqualTo("btcl");
        assertThat(c.status()).isEqualTo("Running");
        assertThat(c.partnerId()).isEqualTo(701);
        assertThat(c.priority()).isEqualTo(5);
        assertThat(c.totalTaskCount()).isEqualTo(10000);
        assertThat(c.sentTaskCount()).isEqualTo(12);
        assertThat(c.failedTaskCount()).isEqualTo(3);
        assertThat(c.defaultViewSeconds()).isEqualTo(15);
        assertThat(c.policy().frequencyCapPerDevicePerDay()).isEqualTo(2);
        assertThat(c.policy().timeBands()).hasSize(1);
        assertThat(c.policy().timeBands().get(0).day()).isEqualTo("WEEKDAYS ONLY");
        assertThat(c.policy().timeBands().get(0).start()).isEqualTo(LocalTime.of(9, 0));
        assertThat(c.policy().timeBands().get(0).allow()).isTrue();
        assertThat(c.scheduleStart()).isNotNull();
        assertThat(c.scheduleEnd()).isNotNull();
        assertThat(c.targeting().specificity(Map.of("zone", "zone1", "district", "dhaka"))).isEqualTo(2);
        assertThat(c.targeting().specificity(Map.of("zone", "zone2", "district", "dhaka"))).isEqualTo(-1);
        assertThat(c.creatives()).extracting(cr -> cr.id()).as("inactive creatives stay out").containsExactly("c-77", "c-78");
        assertThat(c.creatives().get(0).kind()).isEqualTo(MediaKind.VIDEO);
        assertThat(c.creatives().get(0).mediaRef()).isEqualTo("vod-77");
        assertThat(c.creatives().get(0).clickUrl()).isEqualTo("https://cola.example");
        assertThat(c.fields()).containsEntry("message", "Drink it").containsEntry("externalCampaignId", "ext-42");

        Campaign legacy = all.get(1);
        assertThat(legacy.terminal()).as("status 1 = Complete").isTrue();
        assertThat(legacy.creatives()).as("the voice-style pointer column is its one creative").hasSize(1);
        assertThat(legacy.creatives().get(0).mediaRef()).isEqualTo("vod-legacy");
        assertThat(legacy.targeting()).isEqualTo(com.telcobright.seed.campaign.api.Targeting.ANY);
    }

    @Test
    void a_task_is_inserted_as_processing_and_its_life_is_updated_into_the_voice_and_billing_columns() throws Exception {
        Instant t0 = Instant.parse("2026-09-27T06:00:00Z");
        CampaignTask task = new CampaignTask("ad-btcl-aabb-1", "btcl", 42, 701, CampaignKind.AD, "8801711", "c-77", "zone0", "moghbazar", "wifi-9",
            TaskState.PROCESSING, t0, null, null, 0, null, null, Map.of("gw", "wifi-gw2"));

        store.insertTask(task);
        store.bumpCounters("btcl", 42, 0, 0, +1);
        Map<String, Object> row = row("SELECT * FROM campaign_task WHERE uniqueId = 'ad-btcl-aabb-1'");
        assertThat(row).containsEntry("campaign_id", 42).containsEntry("id_partner", 701).containsEntry("phone_number", "8801711")
            .containsEntry("message", "c-77").containsEntry("task_type", "AD").containsEntry("state", 1).containsEntry("status", 15)
            .containsEntry("terminating_called_number", "zone0").containsEntry("originating_calling_number", "moghbazar")
            .containsEntry("client_trans_id", "wifi-9").containsEntry("tenantname", "btcl").containsEntry("start_time_millis", t0.toEpochMilli());
        assertThat((String) row.get("task_detail_json")).contains("wifi-gw2");
        assertThat(row("SELECT PENDING_TASK_COUNT FROM campaign WHERE CAMPAIGN_ID = 42")).containsEntry("pending_task_count", 1);

        CampaignTask done = task.answered(t0.plusSeconds(1)).completed(t0.plusSeconds(17), 15,
            new TaskCharge(586L, "AD_view", BigDecimal.ONE, new BigDecimal("0.50"), "zone=zone0;media=video"), "viewed");
        store.updateTask(done);
        store.bumpCounters("btcl", 42, +1, 0, -1);
        row = row("SELECT * FROM campaign_task WHERE uniqueId = 'ad-btcl-aabb-1'");
        assertThat(String.valueOf(row.get("answered"))).as("TINYINT(1): 1 on MySQL, true on H2").isIn("1", "true");
        assertThat(row).containsEntry("state", 11).containsEntry("status", 11)
            .containsEntry("answer_time_millis", t0.plusSeconds(1).toEpochMilli()).containsEntry("end_time_millis", t0.plusSeconds(17).toEpochMilli())
            .containsEntry("billsec", 15).containsEntry("hangup_cause", "viewed").containsEntry("idpackageaccount", 586L)
            .containsEntry("packageamount", 1.0).containsEntry("uom", "AD_view").containsEntry("inpartnercost", 0.5)
            .containsEntry("isprepaid", "1").containsEntry("matchedprefixcustomer", "zone=zone0;media=video");
        Map<String, Object> counters = row("SELECT SENT_TASK_COUNT, PENDING_TASK_COUNT FROM campaign WHERE CAMPAIGN_ID = 42");
        assertThat(counters).containsEntry("sent_task_count", 13).containsEntry("pending_task_count", 0);

        store.updateTask(task.failed(t0.plusSeconds(3), 2, "abandoned:page-left"));
        row = row("SELECT STATE, STATUS, isPrepaid, idPackageAccount FROM campaign_task WHERE uniqueId = 'ad-btcl-aabb-1'");
        assertThat(row).containsEntry("state", 5).containsEntry("status", 5).containsEntry("isprepaid", "0");
        assertThat(row.get("idpackageaccount")).isNull();

        store.markComplete("btcl", 42);
        assertThat(store.campaigns("btcl").get(0).status()).isEqualTo("Complete");
    }

    private Map<String, Object> row(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            var md = rs.getMetaData();
            Map<String, Object> m = new java.util.HashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) m.put(md.getColumnLabel(i).toLowerCase(), rs.getObject(i));
            return m;
        }
    }

    private static String json(Map<String, Object> m) {
        return "{" + m.entrySet().stream().map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"").collect(Collectors.joining(",")) + "}";
    }
}
