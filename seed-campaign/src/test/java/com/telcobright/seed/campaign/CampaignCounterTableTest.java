package com.telcobright.seed.campaign;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.jdbc.CampaignSchema;
import com.telcobright.seed.campaign.jdbc.Dialect;
import com.telcobright.seed.campaign.jdbc.JdbcCampaignStore;
import com.telcobright.seed.campaign.jdbc.JdbcCampaignStore.Counters;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The campaigns' counters as TRAFFIC — {@link Counters#COUNTER_TABLE} (ad-sphere ARCH-0029) — in BOTH dialects (H2 in MySQL mode and in
 * PostgreSQL mode): a bump is one upsert of {@code campaign_counter} and never a write of the campaign's row; the reader takes the counts
 * from the table; nothing is lost when many bump at once. {@link PostgresCampaignCounterIT} runs the same checks on a real PostgreSQL,
 * where the upsert is {@code ON CONFLICT … DO UPDATE} (H2 in its PostgreSQL mode takes the standard's MERGE).
 * The legacy mode ({@link Counters#CAMPAIGN_ROW}, the default) is {@link JdbcCampaignStoreTest}'s, untouched.
 */
class CampaignCounterTableTest {

    static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

    /** The library's test schema with the counter table beside it; campaign 42's own row says sent 12, failed 3, pending 0. */
    static DataSource open(Dialect d) throws Exception {
        DataSource ds = JdbcCampaignStoreTest.openH2(d);
        try (Connection c = ds.getConnection()) {
            CampaignSchema.createCounterTable(c, d);
            CampaignSchema.createCounterTable(c, d);                   // safe to run twice
        }
        return ds;
    }

    static JdbcCampaignStore store(DataSource ds, Dialect d, Counters counters) {
        return new JdbcCampaignStore(ds, CampaignKind.AD, JdbcCampaignStoreTest::json, d, null, DHAKA, counters);
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a_bump_is_one_upsert_of_the_counter_table_and_the_campaigns_row_is_never_written(Dialect d) throws Exception {
        bumpsLandInTheTableNeverOnTheRow(open(d), d);
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void the_reader_takes_the_counters_from_the_table_and_no_row_is_zeros(Dialect d) throws Exception {
        theReaderTakesTheCountersFromTheTable(open(d), d);
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void campaigns_read_elsewhere_are_overlaid_with_the_tables_counters(Dialect d) throws Exception {
        campaignsFromElsewhereAreOverlaid(open(d), d);
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void the_counter_rows_stamp_is_the_tenants_wall_clock_not_the_jvms(Dialect d) throws Exception {
        theStampIsTheTenantsWallClock(open(d), d);
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a_campaign_reaching_its_quota_is_marked_on_its_own_row_not_in_the_counter_table(Dialect d) throws Exception {
        markCompleteStaysOnTheCampaignsRow(open(d), d);
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void thirty_two_threads_of_a_hundred_bumps_lose_nothing(Dialect d) throws Exception {
        manyBumpsAtOnceLoseNothing(open(d), d, 42);
    }

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a_first_bump_that_loses_the_race_for_the_campaigns_row_is_not_lost(Dialect d) throws Exception {
        firstBumpsThatRaceLoseNothing(open(d), d);
    }

    /** The default is the campaign's row: a store made without a word on the counters bumps the row and leaves the table empty. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a_store_told_nothing_keeps_the_counters_on_the_campaigns_row(Dialect d) throws Exception {
        DataSource ds = open(d);
        JdbcCampaignStore legacy = new JdbcCampaignStore(ds, CampaignKind.AD, JdbcCampaignStoreTest::json, d, null, DHAKA);
        assertThat(legacy.counters()).isEqualTo(Counters.CAMPAIGN_ROW);

        legacy.bumpCounters("btcl", 42, +1, 0, +1);

        assertThat(JdbcCampaignStoreTest.row(ds, "SELECT SENT_TASK_COUNT, PENDING_TASK_COUNT FROM campaign WHERE CAMPAIGN_ID = 42"))
            .containsEntry("sent_task_count", 13).containsEntry("pending_task_count", 1);
        assertThat(count(ds, "SELECT COUNT(*) FROM campaign_counter")).as("the table is not this store's").isZero();
        assertThat(legacy.campaigns("btcl").get(0).sentTaskCount()).as("the reader reads the row").isEqualTo(13);
        List<Campaign> given = legacy.campaigns("btcl");
        assertThat(legacy.withStoredCounters(given)).as("nothing to overlay: the counts are the rows'").isSameAs(given);
    }

    // ── the checks (the real-PostgreSQL IT runs the same) ───────────────────

    static void bumpsLandInTheTableNeverOnTheRow(DataSource ds, Dialect d) throws Exception {
        JdbcCampaignStore store = store(ds, d, Counters.COUNTER_TABLE);
        Map<String, Object> rowBefore = campaignRow(ds, 42);

        store.bumpCounters("btcl", 42, 0, 0, +1);                      // a view is claimed: the first bump MAKES the row
        assertThat(counterRow(ds, 42)).containsEntry("sent_task_count", 0).containsEntry("failed_task_count", 0).containsEntry("pending_task_count", 1);
        store.bumpCounters("btcl", 42, +1, 0, -1);                     // it completes
        store.bumpCounters("btcl", 42, 0, 0, +1);                      // another is claimed
        store.bumpCounters("btcl", 42, 0, +1, -1);                     // and fails
        assertThat(counterRow(ds, 42)).containsEntry("sent_task_count", 1).containsEntry("failed_task_count", 1).containsEntry("pending_task_count", 0);
        assertThat(count(ds, "SELECT COUNT(*) FROM campaign_counter")).as("one row per campaign").isEqualTo(1);

        store.bumpCounters("btcl", 42, 0, 0, -1);                      // a stray end: pending never falls below zero, as on the campaign's row
        assertThat(counterRow(ds, 42)).containsEntry("pending_task_count", 0);
        store.bumpCounters("btcl", 43, 0, +1, -1);                     // a campaign whose FIRST bump takes one away: its row starts at zero too
        assertThat(counterRow(ds, 43)).containsEntry("sent_task_count", 0).containsEntry("failed_task_count", 1).containsEntry("pending_task_count", 0);

        assertThat(campaignRow(ds, 42)).as("the campaign's row — configuration — is not written by a bump: its counters and its stamp stand still")
            .isEqualTo(rowBefore).containsEntry("sent_task_count", 12).containsEntry("failed_task_count", 3);
    }

    static void theReaderTakesTheCountersFromTheTable(DataSource ds, Dialect d) throws Exception {
        JdbcCampaignStore store = store(ds, d, Counters.COUNTER_TABLE);

        Campaign fresh = store.campaigns("btcl").get(0);
        assertThat(fresh.id()).isEqualTo(42);
        assertThat(List.of(fresh.sentTaskCount(), fresh.failedTaskCount(), fresh.pendingTaskCount()))
            .as("no counter row = zeros; the row's own 12 / 3 / 0 are not read").containsExactly(0, 0, 0);

        store.bumpCounters("btcl", 42, +7, +2, +1);

        List<Campaign> all = store.campaigns("btcl");
        assertThat(all).extracting(Campaign::id).as("every campaign of the kind, with or without a counter row").containsExactly(42, 43);
        Campaign c = all.get(0);
        assertThat(List.of(c.sentTaskCount(), c.failedTaskCount(), c.pendingTaskCount())).containsExactly(7, 2, 1);
        assertThat(c.totalTaskCount()).as("the rest of the row is the campaign's").isEqualTo(10000);
        assertThat(c.status()).isEqualTo("Running");
        assertThat(c.creatives()).hasSize(2);
        assertThat(all.get(1).sentTaskCount()).as("a campaign never bumped").isZero();
    }

    static void campaignsFromElsewhereAreOverlaid(DataSource ds, Dialect d) throws Exception {
        JdbcCampaignStore store = store(ds, d, Counters.COUNTER_TABLE);
        store.bumpCounters("btcl", 42, +5, +1, +2);
        // a configuration tree read the campaigns' rows: it carries the rows' own (still) counters — 43's given as 9 / 8 / 7 here
        List<Campaign> rows = store(ds, d, Counters.CAMPAIGN_ROW).campaigns("btcl");
        List<Campaign> fromTheTree = List.of(rows.get(0), rows.get(1).withCounters(9, 8, 7));
        assertThat(fromTheTree.get(0).sentTaskCount()).as("the row's own counter").isEqualTo(12);

        List<Campaign> overlaid = store.withStoredCounters(fromTheTree);

        assertThat(overlaid).extracting(Campaign::id).containsExactly(42, 43);
        Campaign c = overlaid.get(0);
        assertThat(List.of(c.sentTaskCount(), c.failedTaskCount(), c.pendingTaskCount())).as("the table's counters").containsExactly(5, 1, 2);
        assertThat(c.name()).isEqualTo("Cola Q4");
        assertThat(c.creatives()).as("the rest of the campaign is as given").isEqualTo(fromTheTree.get(0).creatives());
        Campaign never = overlaid.get(1);
        assertThat(List.of(never.sentTaskCount(), never.failedTaskCount(), never.pendingTaskCount()))
            .as("no counter row = zeros, never the counts it was given with").containsExactly(0, 0, 0);
        assertThat(never.name()).isEqualTo("voice-style");
        assertThat(store.withStoredCounters(List.of())).isEmpty();
    }

    /** With the JVM in UTC the stamp is Dhaka's wall clock (the store's zone), six hours ahead of the JVM's. */
    static void theStampIsTheTenantsWallClock(DataSource ds, Dialect d) throws Exception {
        TimeZone was = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            JdbcCampaignStore store = store(ds, d, Counters.COUNTER_TABLE);
            store.bumpCounters("btcl", 42, 0, 0, +1);
            assertThat(stampOf(ds, 42)).as("made at Dhaka's wall clock").isCloseTo(LocalDateTime.now(DHAKA), org.assertj.core.api.Assertions.within(2, java.time.temporal.ChronoUnit.MINUTES));
            assertThat(Duration.between(LocalDateTime.now(ZoneId.of("UTC")), stampOf(ds, 42)).toHours()).as("not the JVM's (UTC)").isGreaterThanOrEqualTo(5);
            store.bumpCounters("btcl", 42, +1, 0, -1);
            assertThat(stampOf(ds, 42)).as("and kept there by a later bump").isCloseTo(LocalDateTime.now(DHAKA), org.assertj.core.api.Assertions.within(2, java.time.temporal.ChronoUnit.MINUTES));
        } finally {
            TimeZone.setDefault(was);
        }
    }

    static void markCompleteStaysOnTheCampaignsRow(DataSource ds, Dialect d) throws Exception {
        JdbcCampaignStore store = store(ds, d, Counters.COUNTER_TABLE);

        store.markComplete("btcl", 42);

        assertThat(JdbcCampaignStoreTest.row(ds, "SELECT STATUS FROM campaign WHERE CAMPAIGN_ID = 42")).as("a campaign ending is a configuration change").containsEntry("status", 1);
        assertThat(store.campaigns("btcl").get(0).status()).isEqualTo("Complete");
        assertThat(count(ds, "SELECT COUNT(*) FROM campaign_counter")).as("and no traffic").isZero();
    }

    /** No row to start with, so the threads race for the first one too; every bump adds one to each counter. */
    static void manyBumpsAtOnceLoseNothing(DataSource ds, Dialect d, int campaignId) throws Exception {
        int threads = 32, bumps = 100;
        JdbcCampaignStore store = store(ds, d, Counters.COUNTER_TABLE);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> all = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                all.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < bumps; i++) store.bumpCounters("btcl", campaignId, +1, +1, +1);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : all) f.get(120, TimeUnit.SECONDS);       // a bump that failed fails the test here
        } finally {
            pool.shutdownNow();
        }
        int every = threads * bumps;
        assertThat(counterRow(ds, campaignId)).as(threads + " threads x " + bumps + " bumps")
            .containsEntry("sent_task_count", every).containsEntry("failed_task_count", every).containsEntry("pending_task_count", every);
        assertThat(count(ds, "SELECT COUNT(*) FROM campaign_counter WHERE CAMPAIGN_ID = " + campaignId)).as("one row").isEqualTo(1);
    }

    /**
     * Twenty campaigns never bumped before; for each, 32 threads let go together bump it once: every one of them is a FIRST bump racing
     * for the row. Each row must count all 32 — whichever way the engine settles the race (H2's MERGE fails the loser on the key).
     */
    static void firstBumpsThatRaceLoseNothing(DataSource ds, Dialect d) throws Exception {
        int threads = 32, campaigns = 20;
        JdbcCampaignStore store = store(ds, d, Counters.COUNTER_TABLE);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int n = 0; n < campaigns; n++) {
                int campaignId = 1000 + n;
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> all = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    all.add(pool.submit(() -> {
                        start.await();
                        store.bumpCounters("btcl", campaignId, +1, +1, +1);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : all) f.get(60, TimeUnit.SECONDS);
                assertThat(counterRow(ds, campaignId)).as("campaign " + campaignId + ": " + threads + " first bumps at once")
                    .containsEntry("sent_task_count", threads).containsEntry("failed_task_count", threads).containsEntry("pending_task_count", threads);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(ds, "SELECT COUNT(*) FROM campaign_counter")).as("one row per campaign").isEqualTo(campaigns);
    }

    // ── reads ───────────────────────────────────────────────────────────────

    static Map<String, Object> counterRow(DataSource ds, int campaignId) throws Exception {
        return JdbcCampaignStoreTest.row(ds, "SELECT SENT_TASK_COUNT, FAILED_TASK_COUNT, PENDING_TASK_COUNT FROM campaign_counter WHERE CAMPAIGN_ID = " + campaignId);
    }

    static Map<String, Object> campaignRow(DataSource ds, int campaignId) throws Exception {
        return JdbcCampaignStoreTest.row(ds, "SELECT SENT_TASK_COUNT, FAILED_TASK_COUNT, PENDING_TASK_COUNT, LAST_UPDATED_STAMP, STATUS FROM campaign WHERE CAMPAIGN_ID = " + campaignId);
    }

    static LocalDateTime stampOf(DataSource ds, int campaignId) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT LAST_UPDATED_STAMP FROM campaign_counter WHERE CAMPAIGN_ID = " + campaignId)) {
            assertThat(rs.next()).as("the counter row of " + campaignId).isTrue();
            return rs.getObject(1, LocalDateTime.class);
        }
    }

    static int count(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
