package com.telcobright.seed.sessionflow.records;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.RecordStatement;
import com.telcobright.seed.sessionflow.base.Lab;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabFlow;
import com.telcobright.seed.sessionflow.dependencies.RecordDialect;
import com.telcobright.seed.sessionflow.dependencies.RecordStores;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.SessionJournal;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordTables;
import com.telcobright.statewalk.session.Outcome;
import org.h2.jdbcx.JdbcConnectionPool;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The records road's lab at the base: ONE root ({@code btcl}: btcl > res_44 > res_44_7) on an H2 database in the dialect's mode with
 * billing-core's tables, seen through a {@link RecordingDataSource}; a lab flow whose {@link Writing#write} is the base's reusable
 * {@code writeRecords}. One per test.
 */
final class RecordsLab implements AutoCloseable {

    /** The lab flow, its records road opened up to the test; its SDR rows are the test's. */
    static final class Writing extends LabFlow {
        volatile Function<Object, List<RecordStatement>> sdrRows = sdr -> List.of();

        Writing(RoutesphereKit kit) { super(kit, Map.of(), Lab.hops("carrier-a"), Set.of()); }

        /** The base's reusable method, for a session whose facts name it (its id = the records' call id). */
        void write(List<CdrEvent> tiers, Object sdr) {
            LabContext ctx = new LabContext();
            ctx.facts = new com.telcobright.seed.sessionflow.base.LabFacts(tiers.get(0).callId, 1L, Lab.BTCL_DOOR, 0, "8801");
            writeRecords(ctx, Outcome.SUCCEEDED, tiers, sdr);
        }

        @Override protected List<RecordStatement> sdrStatements(LabContext ctx, Outcome outcome, Object sdr) { return sdrRows.apply(sdr); }
    }

    final String database = "records" + System.nanoTime();
    final JdbcConnectionPool pool;
    final RecordingDataSource db;
    final Writing flow;

    RecordsLab(RecordDialect dialect, SessionJournal journal, Function<RecordStores.Builder, RecordStores.Builder> settings) {
        this.pool = JdbcConnectionPool.create(RecordTables.url(database, dialect), "sa", "");
        RecordTables.create(pool, dialect, false, "btcl", "res_44", "res_44_7");
        this.db = new RecordingDataSource(pool, database);
        RecordStores.Builder stores = dialect == RecordDialect.POSTGRESQL ? RecordStores.builder().postgres("btcl", db) : RecordStores.builder().mysql("btcl", db);
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(Lab.btclTree())).ledger(new InMemoryLedger())
            .records(settings.apply(stores).build()).journal(journal).zone(ZoneId.of("Asia/Dhaka")).build();
        this.flow = new Writing(kit);
    }

    /** The session's records, leaf first, on the tiers its partner's chain names (1, 2 or 3 tiers). */
    static List<CdrEvent> session(String id, int tiers) {
        List<CdrEvent> out = new ArrayList<>();
        if (tiers >= 3) out.add(Records.adView(id, "btcl > res_44 > res_44_7", 3));
        if (tiers >= 2) out.add(Records.adView(id, "btcl > res_44", tiers >= 3 ? 7 : 5));
        out.add(Records.adView(id, "btcl", 44));
        return out;
    }

    boolean awaitWritten() { return flow.kit().records().awaitIdle(20_000); }

    List<String> calls() { return com.telcobright.seed.sessionflow.testkit.H2Locks.calls(database); }

    int count(String sql) throws SQLException { return longs(sql).get(0).intValue(); }

    List<Long> longs(String sql) throws SQLException { return CdrTableSqlTest.longs(pool, sql); }

    List<String> words(String sql) throws SQLException {
        try (Connection c = pool.getConnection(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        }
    }

    /** Every summary_affected row of a tier, read back as summary-service reads it: one list of entries per row. */
    List<List<SummaryServiceReader.AdCallEntry>> summaryRowsOf(String tier) throws Exception {
        List<List<SummaryServiceReader.AdCallEntry>> rows = new ArrayList<>();
        for (String data : words("select data from " + tier + ".summary_affected where entity_type = 'cdr' and op = 'add' order by id")) {
            rows.add(SummaryServiceReader.read(data));
        }
        return rows;
    }

    @Override
    public void close() {
        flow.kit().records().close();
        pool.dispose();
    }
}
