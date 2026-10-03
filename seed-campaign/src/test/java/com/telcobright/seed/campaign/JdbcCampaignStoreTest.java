package com.telcobright.seed.campaign;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.MediaKind;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.seed.campaign.jdbc.CampaignSchema;
import com.telcobright.seed.campaign.jdbc.Dialect;
import com.telcobright.seed.campaign.jdbc.JdbcCampaignStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * routesphere's schema in BOTH dialects (H2 in MySQL mode and in PostgreSQL mode): a campaign written the UI's way
 * comes back whole; a task's life lands in its row. The wifi tenant is the PostgreSQL one (a schema beside Odoo).
 * {@link PostgresCampaignStoreIT} runs the same two checks on a real PostgreSQL when one is named.
 */
class JdbcCampaignStoreTest {

    static JdbcDataSource openH2(Dialect d) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        String mode = d == Dialect.POSTGRES ? "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH" : "MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        ds.setURL("jdbc:h2:mem:" + d.name().toLowerCase() + System.nanoTime() + ";" + mode + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            CampaignSchema.createAll(c, d);
            seed(c, d);
        }
        return ds;
    }

    /** The rows a UI would write. */
    static void seed(Connection c, Dialect d) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO policy (id, name) VALUES (7, 'daytime')");
            st.execute("INSERT INTO time_band (allow_or_restrict, " + d.quote("day") + ", start_time, end_time, policy_id) VALUES ("
                + (d == Dialect.POSTGRES ? "TRUE" : "1") + ", 'WEEKDAYS ONLY', '09:00:00', '17:00:00', 7)");
            st.execute("INSERT INTO schedule_policy (ID, START_TIME, END_TIME, NAME) VALUES (3, '2026-09-01 00:00:00', '2026-12-31 23:59:59', 'q4')");
            st.execute("INSERT INTO campaign (CAMPAIGN_ID, CAMPAIGN_NAME, CAMPAIGN_TYPE, STATUS, ID_PARTNER, PRIORITY, TOTAL_TASK_COUNT, SENT_TASK_COUNT, FAILED_TASK_COUNT, PENDING_TASK_COUNT, POLICY_ID, SCHEDULE_POLICY_ID, MESSAGE, FIELD1, FIELD2, EXTERNAL_CAMPAIGN_ID) "
                + "VALUES (42, 'Cola Q4', 'AD', 10, 701, 5, 10000, 12, 3, 0, 7, 3, 'Drink it', 2, 15, 'ext-42')");
            st.execute("INSERT INTO campaign (CAMPAIGN_ID, CAMPAIGN_NAME, CAMPAIGN_TYPE, STATUS, ID_PARTNER, EXTERNAL_CAMPAIGN_ID, AUDIO_FILE_PATH, MESSAGE) VALUES (43, 'voice-style', 'AD', 1, 0, 'ext-43', 'vod-legacy', 'old way')");
            st.execute("INSERT INTO campaign (CAMPAIGN_ID, CAMPAIGN_NAME, CAMPAIGN_TYPE, STATUS, ID_PARTNER, EXTERNAL_CAMPAIGN_ID) VALUES (44, 'an sms one', 'SMS', 10, 701, 'ext-44')");
            st.execute("INSERT INTO campaign_target (campaign_id, dimension, target_value) VALUES (42, 'zone', 'zone0'), (42, 'zone', 'zone1'), (42, 'district', 'dhaka')");
            st.execute("INSERT INTO campaign_creative (campaign_id, creative_id, kind, media_ref, duration_sec, click_url, caption, active) VALUES (42, 'c-77', 'VIDEO', 'vod-77', 15, 'https://cola.example', 'Cola', 1), (42, 'c-78', 'IMAGE', 'vod-78', 0, NULL, NULL, 1), (42, 'c-79', 'IMAGE', 'vod-79', 0, NULL, NULL, 0)");
        }
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void the_ad_campaigns_come_back_with_status_window_bands_targets_and_creatives(Dialect d) throws Exception {
        campaignsComeBackWhole(openH2(d), d);
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a_task_is_inserted_as_processing_and_its_life_is_updated_into_the_voice_and_billing_columns(Dialect d) throws Exception {
        taskLifeLandsInTheRow(openH2(d), d);
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a_text_longer_than_its_column_is_cut_and_the_task_keeps_its_row(Dialect d) throws Exception {
        aLongTextIsCutNotLost(openH2(d), d);
    }

    /** The widths are the table's own (CampaignSchema): a zone of 64 in a column of 60 must not cost the task its row. */
    static void aLongTextIsCutNotLost(DataSource ds, Dialect d) throws Exception {
        JdbcCampaignStore store = new JdbcCampaignStore(ds, CampaignKind.AD, JdbcCampaignStoreTest::json, d);
        Instant t0 = Instant.parse("2026-10-03T06:00:00Z");
        String zone = "z".repeat(64), site = "s".repeat(61), subject = "8".repeat(80), clientRef = "w".repeat(300);
        CampaignTask task = new CampaignTask("ad-btcl-aabb-2", "btcl", 42, 701, CampaignKind.AD, subject, "c-77", zone, site, clientRef,
            TaskState.PROCESSING, t0, null, null, 0, null, null, Map.of());

        store.insertTask(task);

        Map<String, Object> row = row(ds, "SELECT * FROM campaign_task WHERE uniqueId = 'ad-btcl-aabb-2'");
        assertThat(row).as("the row is there").isNotEmpty();
        assertThat(row).containsEntry("terminating_called_number", "z".repeat(60)).containsEntry("originating_calling_number", "s".repeat(60))
            .containsEntry("phone_number", "8".repeat(60)).containsEntry("client_trans_id", "w".repeat(255));

        store.updateTask(task.completed(t0.plusSeconds(3), 0, new TaskCharge(586L, "u".repeat(51), BigDecimal.ONE, BigDecimal.ZERO, "p".repeat(120)),
            "abandoned:" + "x".repeat(200)));

        row = row(ds, "SELECT * FROM campaign_task WHERE uniqueId = 'ad-btcl-aabb-2'");
        assertThat((String) row.get("hangup_cause")).as("the end of the task is written, its cause cut").hasSize(100).startsWith("abandoned:xxx");
        assertThat((String) row.get("uom")).hasSize(50);
        assertThat((String) row.get("matchedprefixcustomer")).hasSize(100);
        assertThat(row).containsEntry("end_time_millis", t0.plusSeconds(3).toEpochMilli());
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a_task_id_longer_than_its_column_is_refused_by_name_never_cut(Dialect d) throws Exception {
        JdbcCampaignStore store = new JdbcCampaignStore(openH2(d), CampaignKind.AD, JdbcCampaignStoreTest::json, d);
        CampaignTask task = new CampaignTask("ad-" + "t".repeat(48), "btcl", 42, 701, CampaignKind.AD, "8801711", "c-77", "zone0", "site", "wifi-9",
            TaskState.PROCESSING, Instant.parse("2026-10-03T06:00:00Z"), null, null, 0, null, null, Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.insertTask(task))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("51 characters").hasMessageContaining("holds 50");
    }

    static void campaignsComeBackWhole(DataSource ds, Dialect d) {
        JdbcCampaignStore store = new JdbcCampaignStore(ds, CampaignKind.AD, JdbcCampaignStoreTest::json, d);

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
        assertThat(store.dialect()).isEqualTo(d);
    }

    static void taskLifeLandsInTheRow(DataSource ds, Dialect d) throws Exception {
        JdbcCampaignStore store = new JdbcCampaignStore(ds, CampaignKind.AD, JdbcCampaignStoreTest::json, d);
        Instant t0 = Instant.parse("2026-09-27T06:00:00Z");
        CampaignTask task = new CampaignTask("ad-btcl-aabb-1", "btcl", 42, 701, CampaignKind.AD, "8801711", "c-77", "zone0", "moghbazar", "wifi-9",
            TaskState.PROCESSING, t0, null, null, 0, null, null, Map.of("gw", "wifi-gw2"));

        store.insertTask(task);
        store.bumpCounters("btcl", 42, 0, 0, +1);
        Map<String, Object> row = row(ds, "SELECT * FROM campaign_task WHERE uniqueId = 'ad-btcl-aabb-1'");
        assertThat(row).containsEntry("campaign_id", 42).containsEntry("id_partner", 701).containsEntry("phone_number", "8801711")
            .containsEntry("message", "c-77").containsEntry("task_type", "AD").containsEntry("state", 1).containsEntry("status", 15)
            .containsEntry("terminating_called_number", "zone0").containsEntry("originating_calling_number", "moghbazar")
            .containsEntry("client_trans_id", "wifi-9").containsEntry("tenantname", "btcl").containsEntry("start_time_millis", t0.toEpochMilli());
        assertThat((String) row.get("task_detail_json")).contains("wifi-gw2");
        assertThat(row(ds, "SELECT PENDING_TASK_COUNT FROM campaign WHERE CAMPAIGN_ID = 42")).containsEntry("pending_task_count", 1);

        CampaignTask done = task.answered(t0.plusSeconds(1)).completed(t0.plusSeconds(17), 15,
            new TaskCharge(586L, "AD_view", BigDecimal.ONE, new BigDecimal("0.50"), "zone=zone0;media=video"), "viewed");
        store.updateTask(done);
        store.bumpCounters("btcl", 42, +1, 0, -1);
        row = row(ds, "SELECT * FROM campaign_task WHERE uniqueId = 'ad-btcl-aabb-1'");
        assertThat(String.valueOf(row.get("answered"))).isIn("1", "true");
        assertThat(row).containsEntry("state", 11).containsEntry("status", 11)
            .containsEntry("answer_time_millis", t0.plusSeconds(1).toEpochMilli()).containsEntry("end_time_millis", t0.plusSeconds(17).toEpochMilli())
            .containsEntry("billsec", 15).containsEntry("hangup_cause", "viewed").containsEntry("idpackageaccount", 586L)
            .containsEntry("packageamount", 1.0).containsEntry("uom", "AD_view").containsEntry("inpartnercost", 0.5)
            .containsEntry("isprepaid", "1").containsEntry("matchedprefixcustomer", "zone=zone0;media=video");
        Map<String, Object> counters = row(ds, "SELECT SENT_TASK_COUNT, PENDING_TASK_COUNT FROM campaign WHERE CAMPAIGN_ID = 42");
        assertThat(counters).containsEntry("sent_task_count", 13).containsEntry("pending_task_count", 0);

        store.updateTask(task.failed(t0.plusSeconds(3), 2, "abandoned:page-left"));
        row = row(ds, "SELECT STATE, STATUS, isPrepaid, idPackageAccount FROM campaign_task WHERE uniqueId = 'ad-btcl-aabb-1'");
        assertThat(row).containsEntry("state", 5).containsEntry("status", 5).containsEntry("isprepaid", "0");
        assertThat(row.get("idpackageaccount")).isNull();

        store.markComplete("btcl", 42);
        assertThat(store.campaigns("btcl").get(0).status()).isEqualTo("Complete");
    }

    static Map<String, Object> row(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            var md = rs.getMetaData();
            Map<String, Object> m = new java.util.HashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) m.put(md.getColumnLabel(i).toLowerCase(), rs.getObject(i));
            return m;
        }
    }

    static String json(Map<String, Object> m) {
        return "{" + m.entrySet().stream().map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"").collect(Collectors.joining(",")) + "}";
    }

    /**
     * The TIMESTAMP columns keep the TENANT's wall clock, whatever zone the JVM runs in: a container runs in UTC, the legacy switch
     * wrote Dhaka's local time because its JVM ran there. With the JVM in UTC, a task created at 06:00Z lands as 12:00 (Dhaka) when
     * the store is given the tenant's zone — and as 06:00 through the old constructor, which stamps in the JVM's zone and says so.
     */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void the_timestamp_columns_keep_the_tenants_wall_clock_not_the_jvms(Dialect d) throws Exception {
        TimeZone was = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            DataSource ds = openH2(d);
            Instant t0 = Instant.parse("2026-10-03T06:00:00Z");
            JdbcCampaignStore dhaka = new JdbcCampaignStore(ds, CampaignKind.AD, JdbcCampaignStoreTest::json, d, null, ZoneId.of("Asia/Dhaka"));
            JdbcCampaignStore jvm = new JdbcCampaignStore(ds, CampaignKind.AD, JdbcCampaignStoreTest::json, d);

            dhaka.insertTask(task("ad-btcl-zone-dhaka", t0));
            jvm.insertTask(task("ad-btcl-zone-jvm", t0));

            assertThat(createdStamp(ds, "ad-btcl-zone-dhaka")).as("the tenant's wall clock, Dhaka").isEqualTo(LocalDateTime.parse("2026-10-03T12:00:00"));
            assertThat(createdStamp(ds, "ad-btcl-zone-jvm")).as("the old constructor: the JVM's zone, UTC here").isEqualTo(LocalDateTime.parse("2026-10-03T06:00:00"));
            assertThat(dhaka.zone()).isEqualTo(ZoneId.of("Asia/Dhaka"));
        } finally {
            TimeZone.setDefault(was);
        }
    }

    private static CampaignTask task(String uniqueId, Instant createdAt) {
        return new CampaignTask(uniqueId, "btcl", 42, 701, CampaignKind.AD, "8801711", "c-77", "zone0", "site", "wifi-9",
            TaskState.PROCESSING, createdAt, null, null, 0, null, null, Map.of());
    }

    private static LocalDateTime createdStamp(DataSource ds, String uniqueId) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT CREATED_STAMP FROM campaign_task WHERE uniqueId = '" + uniqueId + "'")) {
            assertThat(rs.next()).as("the row of " + uniqueId).isTrue();
            return rs.getObject(1, LocalDateTime.class);
        }
    }
}
