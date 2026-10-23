package com.telcobright.seed.sessionflow.records;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.api.RecordStatement;
import com.telcobright.seed.sessionflow.base.Lab;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabFlow;
import com.telcobright.seed.sessionflow.dependencies.RecordStores;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.seed.sessionflow.testkit.RecordTables;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §A · The application's part: the three hooks and their defaults — {@code cdrStatements} (billing-core's {@code cdr} row in the dialect of the
 * record's ROOT's store, found by the record's hierarchy), {@code sdrStatements} (none), {@code summaryEntryOf} (billing-core's entry).
 */
class RecordHooksTest {

    private final JdbcConnectionPool btcl = JdbcConnectionPool.create(RecordTables.url("hooks-btcl" + System.nanoTime(),
        com.telcobright.seed.sessionflow.dependencies.RecordDialect.POSTGRESQL), "sa", "");
    private final JdbcConnectionPool tele2 = JdbcConnectionPool.create(RecordTables.url("hooks-tele2" + System.nanoTime(),
        com.telcobright.seed.sessionflow.dependencies.RecordDialect.MYSQL), "sa", "");

    @AfterEach
    void close() { btcl.dispose(); tele2.dispose(); }

    /** The lab flow with the hooks opened up to this test. */
    static final class HookedFlow extends LabFlow {
        HookedFlow(RoutesphereKit kit) { super(kit, Map.of(), Lab.hops("carrier-a"), Set.of()); }
        List<RecordStatement> cdr(CdrEvent record) { return cdrStatements(null, record); }
        List<RecordStatement> sdr(LabContext ctx) { return sdrStatements(ctx, null, "sdr"); }
        Object entry(CdrEvent record) { return summaryEntryOf(null, record); }
    }

    private HookedFlow flow() {
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(Lab.btclTree())).ledger(new InMemoryLedger()).cdr(new RecordingCdrSink())
            .records(RecordStores.builder().postgres("btcl", btcl).mysql("tele2", tele2).build()).zone(ZoneId.of("Asia/Dhaka")).build();
        return new HookedFlow(kit);
    }

    @Test
    void theDefaultCdrStatements_areTheProbeAndTheInsert_inTheDialectOfTheRecordsRootsStore() {
        HookedFlow flow = flow();
        List<RecordStatement> reseller = flow.cdr(Records.adView("v-1", "btcl > res_44 > res_44_7", 3));
        assertThat(reseller).extracting(RecordStatement::probe).containsExactly(true, false);
        assertThat(reseller.get(0).sql()).contains("res_44_7.cdrerror where ChannelCallUuid");
        assertThat(reseller.get(1).params()).hasSize(110);
        List<RecordStatement> mysql = flow.cdr(Records.adView("t-1", "tele2", 1));
        assertThat(mysql.get(0).sql()).isEqualTo("select 1 from tele2.cdr where UniqueBillId = ?");
        assertThat(mysql.get(1).params()).hasSize(104);
    }

    @Test
    void theDefaultSdrStatements_areNone_andTheDefaultEntry_isBillingCoresEntry() {
        HookedFlow flow = flow();
        assertThat(flow.sdr(new LabContext())).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) flow.entry(Records.adView("v-1", "btcl > res_44", 701));
        assertThat(entry).containsOnlyKeys("Cdr", "Chargeables");
    }

    @Test
    void aRecordOfARootTheRoadHasNoStoreFor_isRefusedInWords() {
        assertThatThrownBy(() -> flow().cdr(Records.adView("x-1", "ccl > ccl_44", 701)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("no store for the root 'ccl'");
    }
}
