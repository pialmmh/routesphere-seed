package com.telcobright.seed.sessionflow.records;

import com.telcobright.seed.sessionflow.api.RecordId;
import com.telcobright.seed.sessionflow.api.RecordStatement;
import com.telcobright.seed.sessionflow.dependencies.RecordDialect;
import com.telcobright.seed.sessionflow.internal.IdBook;
import com.telcobright.seed.sessionflow.internal.StatementRunner;
import com.telcobright.seed.sessionflow.testkit.RecordTables;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §A · The once rule of a lane's OWN statements (no probe): the record's first change decides — a row changed = written now; none (its
 * {@code ON CONFLICT DO NOTHING}, its {@code INSERT IGNORE}) = written before, and the rest of the record does not run. A {@link RecordId}
 * is one number per key in a try.
 */
class StatementRunnerTest {

    private final JdbcConnectionPool pool = JdbcConnectionPool.create(RecordTables.url("runner" + System.nanoTime(), RecordDialect.POSTGRESQL), "sa", "");

    @AfterEach
    void close() { pool.dispose(); }

    @Test
    void aLanesOwnInsertThatChangesNothing_isWrittenBefore_andTheRestOfItsRecordDoesNotRun() throws Exception {
        try (Connection c = pool.getConnection()) {
            c.createStatement().execute("create table lane_sdr (id varchar primary key, note varchar)");
            c.createStatement().execute("create table lane_extra (id varchar, note varchar)");
            List<RecordStatement> record = List.of(
                RecordStatement.of("insert into lane_sdr (id, note) values (?, ?) on conflict do nothing", "s-1", "first"),
                RecordStatement.of("insert into lane_extra (id, note) values (?, ?)", "s-1", "after the first change"));
            assertThat(StatementRunner.writes(c, record, new IdBook(c))).isTrue();
            assertThat(StatementRunner.writes(c, record, new IdBook(c))).as("its first change changed nothing").isFalse();
            assertThat(CdrTableSqlTest.count(pool, "lane_extra")).as("the rest did not run the second time").isEqualTo(1);
        }
    }

    @Test
    void aRecordId_isOneNumberPerKeyInATry_theSeedAskedOnce() throws Exception {
        try (Connection c = pool.getConnection()) {
            c.createStatement().execute("create table lane_rows (id bigint, k varchar)");
            c.createStatement().execute("insert into lane_rows values (40, 'old')");
            String seed = "select coalesce(max(id), 0) from lane_rows";
            IdBook ids = new IdBook(c);
            RecordId a = new RecordId(seed, "a"), b = new RecordId(seed, "b");
            StatementRunner.writes(c, List.of(RecordStatement.of("insert into lane_rows values (?, ?)", a, "a")), ids);
            StatementRunner.writes(c, List.of(RecordStatement.of("insert into lane_rows values (?, ?)", b, "b")), ids);
            assertThat(ids.numberOf(a)).isEqualTo(41L);
            assertThat(ids.numberOf(b)).isEqualTo(42L);
            assertThat(ids.given(new RecordId(seed, "never asked"))).isZero();
        }
    }
}
