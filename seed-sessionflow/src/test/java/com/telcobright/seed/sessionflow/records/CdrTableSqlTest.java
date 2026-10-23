package com.telcobright.seed.sessionflow.records;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.RecordStatement;
import com.telcobright.seed.sessionflow.dependencies.RecordDialect;
import com.telcobright.seed.sessionflow.dependencies.RecordStore;
import com.telcobright.seed.sessionflow.internal.CdrColumns;
import com.telcobright.seed.sessionflow.internal.CdrTableSql;
import com.telcobright.seed.sessionflow.internal.IdBook;
import com.telcobright.seed.sessionflow.internal.StatementRunner;
import com.telcobright.seed.sessionflow.testkit.RecordTables;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C6 · The base's default CDR SQL on H2 in PostgreSQL mode and in MySQL mode: billing-core's {@code cdr} row of a tier record, in the tier's
 * schema, its columns and values as billing-core's ingest maps the wire — and written ONCE by billing-core's rule (a probe of its key under
 * the lock, then a plain INSERT): the second write of a record leaves one row and is not summed, with or without MySQL's by-hand index.
 */
class CdrTableSqlTest {

    private final List<JdbcConnectionPool> pools = new ArrayList<>();

    @AfterEach
    void close() { pools.forEach(JdbcConnectionPool::dispose); }

    private JdbcConnectionPool db(RecordDialect dialect, boolean byHandIndex) {
        JdbcConnectionPool pool = JdbcConnectionPool.create(RecordTables.url("cdr" + System.nanoTime(), dialect), "sa", "");
        pools.add(pool);
        RecordTables.create(pool, dialect, byHandIndex, "btcl", "res_44");
        return pool;
    }

    private static boolean write(Connection c, CdrEvent record, RecordStore store) throws SQLException {
        return StatementRunner.writes(c, CdrTableSql.statementsOf(record, store), new IdBook(c));
    }

    @Test
    void postgres_theRecordIsWrittenOnce_intoItsTiersSchema_withBillingCoresColumnsAndValues() throws Exception {
        JdbcConnectionPool pool = db(RecordDialect.POSTGRESQL, false);
        RecordStore store = new RecordStore("btcl", pool, RecordDialect.POSTGRESQL, 7);
        CdrEvent leaf = Records.adView("v-1", "btcl > res_44", 701);
        try (Connection c = pool.getConnection()) {
            assertThat(write(c, leaf, store)).as("written now").isTrue();
            assertThat(write(c, leaf, store)).as("the replay: billing-core's key is in the tier already").isFalse();
            assertThat(write(c, leaf, new RecordStore("btcl", pool, RecordDialect.POSTGRESQL, 7))).isFalse();
        }
        assertThat(count(pool, "res_44.cdr")).isEqualTo(1);
        assertThat(count(pool, "btcl.cdr")).as("only the tier's own schema").isZero();
        Map<String, Object> row = row(pool, "res_44.cdr");
        assertThat(row).containsEntry("switchid", 7).containsEntry("idcall", 1L).containsEntry("filename", "switch:records")
            .containsEntry("servicegroup", 30).containsEntry("chargingstatus", 1).containsEntry("uniquebillid", "v-1")
            .containsEntry("channelcalluuid", "v-1").containsEntry("resellerhierarchy", "btcl > res_44").containsEntry("inpartnerid", 701)
            .containsEntry("hangupcause", "NORMAL_CLEARING").containsEntry("areacodeorlata", "NORMAL_CLEARING").containsEntry("codec", "video")
            .containsEntry("incomingroute", "cola-eid").containsEntry("outgoingroute", "10.20.0.1").containsEntry("originatingip", "10.20.0.1")
            .containsEntry("matchedprefixcustomer", "R701").containsEntry("inpartneruom", "BDT").containsEntry("idpackageaccount", 3044L)
            .containsEntry("prepaid", 1).containsEntry("originatingcallednumber", "R701");
        assertThat(row.get("starttime")).isEqualTo(LocalDateTime.of(2026, 10, 23, 17, 10, 5));
        assertThat(row.get("signalingstarttime")).isEqualTo(row.get("starttime"));
        assertThat(row.get("answertime")).isEqualTo(LocalDateTime.of(2026, 10, 23, 17, 10, 6));
        assertThat(row.get("connecttime")).isEqualTo(row.get("answertime"));
        assertThat((BigDecimal) row.get("inpartnercost")).isEqualByComparingTo("0.50");
        assertThat((BigDecimal) row.get("customerrate")).isEqualByComparingTo("0.50");
        assertThat((BigDecimal) row.get("duration1")).as("a stated group: no plan rounds it").isEqualByComparingTo("15");
        assertThat((BigDecimal) row.get("roundedduration")).isEqualByComparingTo("15");
        assertThat(row.get("supplierrate")).isNull();
    }

    @Test
    void mysql_theLegacy104_writtenOnceByTheProbe_withAndWithoutTheByHandUniqueIndex() throws Exception {
        for (boolean byHandIndex : new boolean[] {false, true}) {
            JdbcConnectionPool pool = db(RecordDialect.MYSQL, byHandIndex);
            RecordStore store = new RecordStore("btcl", pool, RecordDialect.MYSQL, 0);
            CdrEvent root = Records.adView("v-2", "btcl", 9);
            try (Connection c = pool.getConnection()) {
                assertThat(write(c, root, store)).isTrue();
                assertThat(write(c, root, store)).as("billing-core's MySQL key UniqueBillId, by-hand index " + byHandIndex).isFalse();
            }
            assertThat(count(pool, "btcl.cdr")).isEqualTo(1);
            assertThat(columnsOf(pool, "btcl", "cdr")).as("the MySQL table has no wire columns: the INSERT names the 104").hasSize(104);
        }
    }

    @Test
    void theIdCall_isSeededUnderTheLock_fromTheGreaterOfCdrAndCdrerror_asBillingCoreSeedsIt() throws Exception {
        JdbcConnectionPool pool = db(RecordDialect.POSTGRESQL, false);
        RecordStore store = new RecordStore("btcl", pool, RecordDialect.POSTGRESQL, 0);
        try (Connection c = pool.getConnection()) {
            c.createStatement().execute("insert into res_44.cdrerror (SwitchId, IdCall, SequenceNumber, ServiceGroup, StartTime) values (0, 41, 1, 30, now())");
            IdBook ids = new IdBook(c);
            assertThat(StatementRunner.writes(c, CdrTableSql.statementsOf(Records.adView("v-3", "btcl > res_44", 701), store), ids)).isTrue();
            assertThat(StatementRunner.writes(c, CdrTableSql.statementsOf(Records.adView("v-4", "btcl > res_44", 701), store), ids)).isTrue();
        }
        assertThat(longs(pool, "select IdCall from res_44.cdr order by IdCall")).containsExactly(42L, 43L);
    }

    @Test
    void postgres_aRecordWhoseKeyBillingCorePutInCdrerror_isWrittenAlready() throws Exception {
        JdbcConnectionPool pool = db(RecordDialect.POSTGRESQL, false);
        try (Connection c = pool.getConnection()) {
            c.createStatement().execute("insert into res_44.cdrerror (SwitchId, IdCall, SequenceNumber, ServiceGroup, StartTime, ChannelCallUuid)"
                + " values (0, 5, 1, 30, now(), 'v-5')");
            assertThat(write(c, Records.adView("v-5", "btcl > res_44", 701), new RecordStore("btcl", pool, RecordDialect.POSTGRESQL, 0))).isFalse();
        }
        assertThat(count(pool, "res_44.cdr")).isZero();
    }

    @Test
    void theInsertNamesBillingCoresColumns_inItsOrder_andTheLabsTablesHaveExactlyThem() throws Exception {
        assertThat(CdrColumns.columns(true)).hasSize(110).endsWith("ResellerHierarchy", "ChannelCallUuid", "HangupCause", "InPartnerUom",
            "IdPackageAccount", "PackageAmount");
        assertThat(CdrColumns.columns(false)).hasSize(104).startsWith("SwitchId", "IdCall", "SequenceNumber", "FileName").endsWith("SignalingStartTime");
        List<String> lower = CdrColumns.columns(true).stream().map(String::toLowerCase).toList();
        assertThat(columnsOf(db(RecordDialect.POSTGRESQL, false), "res_44", "cdr")).containsExactlyElementsOf(lower);
        RecordStatement insert = CdrTableSql.statementsOf(Records.adView("v-6", "btcl > res_44", 701),
            new RecordStore("btcl", pools.get(0), RecordDialect.POSTGRESQL, 0)).get(1);
        assertThat(insert.sql()).startsWith("insert into res_44.cdr (SwitchId,IdCall,");
        assertThat(insert.params()).hasSize(110);
    }

    @Test
    void aTierThatIsNotAPlainSchemaName_isRefused_neverPutIntoTheSql() {
        CdrEvent hostile = Records.adView("v-7", "btcl > res_44", 701);
        hostile.tenant = "res_44.cdr; drop table x --";
        assertThatThrownBy(() -> CdrTableSql.statementsOf(hostile, new RecordStore("btcl", db(RecordDialect.POSTGRESQL, false), RecordDialect.POSTGRESQL, 0)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a schema name");
    }

    // ── reading the lab ─────────────────────────────────────────────────────

    static int count(JdbcConnectionPool pool, String table) throws SQLException {
        return longs(pool, "select count(*) from " + table).get(0).intValue();
    }

    static List<Long> longs(JdbcConnectionPool pool, String sql) throws SQLException {
        try (Connection c = pool.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            List<Long> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getLong(1));
            return out;
        }
    }

    static Map<String, Object> row(JdbcConnectionPool pool, String table) throws SQLException {
        try (Connection c = pool.getConnection(); ResultSet rs = c.createStatement().executeQuery("select * from " + table)) {
            Map<String, Object> row = new TreeMap<>();
            rs.next();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                Object value = rs.getObject(i);
                if (value instanceof java.sql.Timestamp t) value = t.toLocalDateTime();
                row.put(rs.getMetaData().getColumnName(i).toLowerCase(), value);
            }
            return row;
        }
    }

    static List<String> columnsOf(JdbcConnectionPool pool, String schema, String table) throws SQLException {
        try (Connection c = pool.getConnection(); ResultSet rs = c.getMetaData().getColumns(null, schema, table, null)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString("COLUMN_NAME").toLowerCase());
            return out;
        }
    }
}
