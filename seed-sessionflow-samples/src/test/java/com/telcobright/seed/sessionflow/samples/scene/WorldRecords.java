package com.telcobright.seed.sessionflow.samples.scene;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.sessionflow.dependencies.RecordDialect;
import com.telcobright.seed.sessionflow.dependencies.RecordStores;
import com.telcobright.seed.sessionflow.testkit.RecordTables;
import org.h2.jdbcx.JdbcConnectionPool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * The world's records stores (P1-R §C): ONE H2 database per root in PostgreSQL mode, billing-core's tables in every tier of its tree
 * ({@code btcl}: btcl, res_44, res_44_7 · {@code ccl}: ccl, ccl_44) and the ad's {@code ad_sdr} in the root's schema; and the readers a
 * test asks — the rows, and a {@code summary_affected} row read back as summary-service decodes it (base64 → gunzip → JSON).
 */
public final class WorldRecords implements AutoCloseable {

    /** The ad's SDR table: one row per view, its session id the key (a second end of the view writes nothing). */
    public static final String AD_SDR = "(session_id VARCHAR PRIMARY KEY, campaign_id INTEGER, creative_id VARCHAR, handed_over BOOLEAN,"
        + " shown BOOLEAN, watched_seconds INTEGER, outcome VARCHAR NOT NULL, end_cause VARCHAR)";
    private static final ObjectMapper JSON = new ObjectMapper();

    public final JdbcConnectionPool btcl = rootDatabase(World.BTCL, "btcl", "res_44", "res_44_7");
    public final JdbcConnectionPool ccl = rootDatabase(World.CCL, "ccl", "ccl_44");

    /** Both roots' stores, PostgreSQL. */
    public RecordStores.Builder stores() { return RecordStores.builder().postgres(World.BTCL, btcl).postgres(World.CCL, ccl); }

    private static JdbcConnectionPool rootDatabase(String root, String... tiers) {
        JdbcConnectionPool pool = JdbcConnectionPool.create(RecordTables.url("world-" + root + System.nanoTime(), RecordDialect.POSTGRESQL), "sa", "");
        RecordTables.create(pool, RecordDialect.POSTGRESQL, false, tiers);
        execute(pool, "CREATE TABLE " + root + ".ad_sdr " + AD_SDR);
        return pool;
    }

    public static void execute(JdbcConnectionPool db, String sql) {
        try (Connection c = db.getConnection()) {
            c.createStatement().execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
    }

    /** One column of every row a query answers, as text. */
    public static List<String> column(JdbcConnectionPool db, String sql) {
        try (Connection c = db.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
    }

    public static int count(JdbcConnectionPool db, String table) { return Integer.parseInt(column(db, "select count(*) from " + table).get(0)); }

    /** The entries of every summary_affected row of a tier: one list per row, in the rows' order. */
    public static List<List<JsonNode>> summaryRows(JdbcConnectionPool db, String tier) {
        List<List<JsonNode>> rows = new ArrayList<>();
        for (String data : column(db, "select data from " + tier + ".summary_affected where entity_type = 'cdr' and op = 'add' order by id")) {
            List<JsonNode> entries = new ArrayList<>();
            decoded(data).forEach(entries::add);
            rows.add(entries);
        }
        return rows;
    }

    /** summary-service's {@code OutboxCodec.decode}, then the JSON. */
    public static JsonNode decoded(String data) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {
            return JSON.readTree(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("not a summary blob: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        btcl.dispose();
        ccl.dispose();
    }
}
