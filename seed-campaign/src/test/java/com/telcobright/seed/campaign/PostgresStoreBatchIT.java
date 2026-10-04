package com.telcobright.seed.campaign;

import com.telcobright.seed.campaign.jdbc.CampaignSchema;
import com.telcobright.seed.campaign.jdbc.Dialect;
import com.telcobright.seed.campaign.jdbc.JdbcCampaignStore.Counters;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link StoreBatchAndRepairTest}'s checks on a REAL PostgreSQL (ad-sphere ARCH-0043 R1-1): the batch in one transaction, the row made
 * only when it is not there ({@code INSERT … SELECT … WHERE NOT EXISTS}, with typed parameters), the counter's upsert with the batch's
 * sums ({@code ON CONFLICT … DO UPDATE}), and the start's repair under {@code FOR UPDATE}. Each check has a schema of its own, made and
 * dropped here. Runs only when {@code -Dseed.pg.url=jdbc:postgresql://host:port/db} names a throwaway database.
 */
class PostgresStoreBatchIT {

    interface Check { void on(PGSimpleDataSource ds) throws Exception; }

    @Test
    void a_batch_is_one_transaction_all_of_it_or_none() throws Exception {
        for (Counters where : Counters.values()) inASchemaOfItsOwn(ds -> StoreBatchAndRepairTest.aBatchIsAllOrNothing(ds, Dialect.POSTGRES, where));
    }

    @Test
    void two_thousand_ends_of_one_campaign_are_one_counter_statement_not_two_thousand() throws Exception {
        for (Counters where : Counters.values()) inASchemaOfItsOwn(ds -> StoreBatchAndRepairTest.oneCounterStatementACampaign(ds, Dialect.POSTGRES, where));
    }

    @Test
    void a_batch_written_twice_makes_no_second_row_and_an_update_that_finds_no_row_makes_it() throws Exception {
        inASchemaOfItsOwn(ds -> StoreBatchAndRepairTest.writtenTwiceTheRowsEndRight(ds, Dialect.POSTGRES));
    }

    @Test
    void a_start_closes_what_a_dead_process_left_and_sets_the_counters_from_the_task_rows() throws Exception {
        for (Counters where : Counters.values()) inASchemaOfItsOwn(ds -> StoreBatchAndRepairTest.theStartRepairs(ds, Dialect.POSTGRES, where));
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
            }
            check.on(ds);
        } finally {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
