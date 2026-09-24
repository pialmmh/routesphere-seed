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
 * The same two checks as {@link JdbcCampaignStoreTest}, on a REAL PostgreSQL, in a schema of its own beside
 * {@code public} — the wifi tenant's shape (owner, 2026-09-25: the tables live in the Odoo database). Runs only when
 * {@code -Dseed.pg.url=jdbc:postgresql://host:port/db} names a throwaway database (trust auth or a user in the URL);
 * it creates and drops a schema {@code seed_it_<nanos>} there.
 */
class PostgresCampaignStoreIT {

    @Test
    void the_routesphere_schema_lives_in_its_own_postgres_schema_beside_odoo() throws Exception {
        String url = System.getProperty("seed.pg.url", "");
        assumeTrue(!url.isBlank(), "no -Dseed.pg.url: the real-PostgreSQL check is skipped");
        String schema = "seed_it_" + System.nanoTime();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setCurrentSchema(schema);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        try (Connection c = ds.getConnection()) {
            CampaignSchema.createAll(c, Dialect.POSTGRES);
            JdbcCampaignStoreTest.seed(c, Dialect.POSTGRES);
            assertThat(c.getSchema()).isEqualTo(schema);
        }
        try {
            JdbcCampaignStoreTest.campaignsComeBackWhole(ds, Dialect.POSTGRES);
            JdbcCampaignStoreTest.taskLifeLandsInTheRow(ds, Dialect.POSTGRES);
        } finally {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
