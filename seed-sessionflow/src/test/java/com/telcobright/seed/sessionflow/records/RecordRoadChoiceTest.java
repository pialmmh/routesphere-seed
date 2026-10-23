package com.telcobright.seed.sessionflow.records;

import com.telcobright.seed.sessionflow.base.Lab;
import com.telcobright.seed.sessionflow.dependencies.RecordDialect;
import com.telcobright.seed.sessionflow.dependencies.RecordStores;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordTables;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C7 · The road choice: ONE road per lane, like F3's one recovery road — a records store and a CDR sink together are refused at build, in
 * words that name both; neither is refused; each alone builds, and a lane on the CDR sink is the lane it was (its tests unchanged).
 */
class RecordRoadChoiceTest {

    private final JdbcConnectionPool pool = JdbcConnectionPool.create(RecordTables.url("choice" + System.nanoTime(), RecordDialect.POSTGRESQL), "sa", "");

    @AfterEach
    void close() { pool.dispose(); }

    private static RoutesphereKit.Builder kit() {
        return RoutesphereKit.builder().tenants(TenantLookup.of(Lab.btclTree())).ledger(new InMemoryLedger()).zone(ZoneId.of("Asia/Dhaka"));
    }

    private RecordStores stores() { return RecordStores.builder().postgres("btcl", pool).build(); }

    @Test
    void aRecordsStoreAndACdrSinkTogether_areRefusedAtBuild_inWordsThatNameBoth_C7() {
        assertThatThrownBy(() -> kit().cdr(new RecordingCdrSink()).records(stores()).build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("one road per lane").hasMessageContaining("CDR sink").hasMessageContaining("records road");
    }

    @Test
    void neitherRoad_isRefused_thereIsNoDefault() {
        assertThatThrownBy(() -> kit().build()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cdr (a CDR sink) or records (the records road)");
    }

    @Test
    void eachRoadAlone_builds_theSinksLaneHasNoRecordsRoad_theRecordsLaneNoSink() {
        RecordingCdrSink sink = new RecordingCdrSink();
        RoutesphereKit onTheSink = kit().cdr(sink).build();
        assertThat(onTheSink.cdr()).isSameAs(sink);
        assertThat(onTheSink.records()).isNull();
        RoutesphereKit onRecords = kit().records(stores()).build();
        assertThat(onRecords.cdr()).isNull();
        assertThat(onRecords.records()).isNotNull();
        assertThat(onRecords.recordStores().of("btcl")).isPresent();
        onRecords.records().close();
    }
}
