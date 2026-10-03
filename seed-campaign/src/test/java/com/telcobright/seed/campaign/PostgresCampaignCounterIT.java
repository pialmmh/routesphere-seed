package com.telcobright.seed.campaign;

import com.telcobright.seed.campaign.jdbc.CampaignSchema;
import com.telcobright.seed.campaign.jdbc.Dialect;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link CampaignCounterTableTest}'s checks on a REAL PostgreSQL, where the counters' upsert is {@code INSERT … ON CONFLICT (CAMPAIGN_ID) DO
 * UPDATE} — the wording H2 does not take, so only this test runs it. Each check has a schema of its own ({@code seed_it_<nanos>}), made
 * and dropped here. Runs only when {@code -Dseed.pg.url=jdbc:postgresql://host:port/db} names a throwaway database, as
 * {@link PostgresCampaignStoreIT} does.
 */
class PostgresCampaignCounterIT {

    interface Check { void on(PGSimpleDataSource ds) throws Exception; }

    @Test
    void a_bump_is_one_upsert_of_the_counter_table_and_the_campaigns_row_is_never_written() throws Exception {
        inASchemaOfItsOwn(ds -> CampaignCounterTableTest.bumpsLandInTheTableNeverOnTheRow(ds, Dialect.POSTGRES));
    }

    @Test
    void the_reader_takes_the_counters_from_the_table_and_no_row_is_zeros() throws Exception {
        inASchemaOfItsOwn(ds -> CampaignCounterTableTest.theReaderTakesTheCountersFromTheTable(ds, Dialect.POSTGRES));
    }

    @Test
    void campaigns_read_elsewhere_are_overlaid_with_the_tables_counters() throws Exception {
        inASchemaOfItsOwn(ds -> CampaignCounterTableTest.campaignsFromElsewhereAreOverlaid(ds, Dialect.POSTGRES));
    }

    @Test
    void the_counter_rows_stamp_is_the_tenants_wall_clock_not_the_jvms() throws Exception {
        inASchemaOfItsOwn(ds -> CampaignCounterTableTest.theStampIsTheTenantsWallClock(ds, Dialect.POSTGRES));
    }

    @Test
    void a_campaign_reaching_its_quota_is_marked_on_its_own_row_not_in_the_counter_table() throws Exception {
        inASchemaOfItsOwn(ds -> CampaignCounterTableTest.markCompleteStaysOnTheCampaignsRow(ds, Dialect.POSTGRES));
    }

    @Test
    void thirty_two_threads_of_a_hundred_bumps_lose_nothing() throws Exception {
        inASchemaOfItsOwn(ds -> CampaignCounterTableTest.manyBumpsAtOnceLoseNothing(ds, Dialect.POSTGRES, 42));
    }

    @Test
    void a_first_bump_that_loses_the_race_for_the_campaigns_row_is_not_lost() throws Exception {
        inASchemaOfItsOwn(ds -> CampaignCounterTableTest.firstBumpsThatRaceLoseNothing(ds, Dialect.POSTGRES));
    }

    private static void inASchemaOfItsOwn(Check check) throws Exception {
        String url = System.getProperty("seed.pg.url", "");
        assumeTrue(!url.isBlank(), "no -Dseed.pg.url: the real-PostgreSQL check is skipped");
        String schema = "seed_it_" + System.nanoTime();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setCurrentSchema(schema);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        try {
            try (Connection c = ds.getConnection()) {
                CampaignSchema.createAll(c, Dialect.POSTGRES);
                CampaignSchema.createCounterTable(c, Dialect.POSTGRES);
                JdbcCampaignStoreTest.seed(c, Dialect.POSTGRES);
                assertThat(c.getSchema()).isEqualTo(schema);
            }
            check.on(ds);
        } finally {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
