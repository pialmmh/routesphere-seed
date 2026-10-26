package com.telcobright.seed.sessionflow.base;

import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.base.LabEvents.Answer;
import com.telcobright.seed.sessionflow.base.LabEvents.Dial;
import com.telcobright.seed.sessionflow.base.LabEvents.Hangup;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.RecordingCdrSink;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.persistence.PersistenceMode;
import com.telcobright.statewalk.persistence.jdbc.JdbcPersistenceProvider;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §L.4 · One table per lane: each application registers its OWN supervisor name ({@code flow.name()}), and statewalk's JDBC store names
 * the lane's table after it — two lanes on ONE database (a POOLED DataSource) write two tables, each holding only its own sessions; the
 * final rows are kept (statewalk 4.0 §0.8).
 */
class OneTablePerLaneTest {

    private final JdbcConnectionPool pool = JdbcConnectionPool.create("jdbc:h2:mem:lanes" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
    private final List<RoutesphereEngine<LabContext>> engines = new ArrayList<>();

    @AfterEach
    void close() {
        engines.forEach(RoutesphereEngine::close);
        pool.dispose();
    }

    private TestChannel<String, StatemachineEvent> lane(String supervisorName) {
        InMemoryLedger ledger = new InMemoryLedger().fund("btcl", 1, "100.00");
        RoutesphereKit kit = RoutesphereKit.builder().tenants(TenantLookup.of(Lab.btclTree())).identification(List.of(Lab.RULES.get(0)))
            .ledger(ledger).cdr(new RecordingCdrSink()).zone(ZoneId.of("Asia/Dhaka")).settings(Lab.settings(0)).build();
        LabFlow flow = new LabFlow(kit, supervisorName, Map.of(), Lab.hops("carrier-a"), Set.of());
        TestChannel<String, StatemachineEvent> wire = new TestChannel<>(supervisorName);
        engines.add(RoutesphereEngine.of(new LabSupervisor(flow)).signaling(LabCall.spec()).channel(wire, Lab::decode)
            .persistence(PersistenceMode.SAVE, () -> new JdbcPersistenceProvider(pool)).start());
        return wire;
    }

    private static void call(TestChannel<String, StatemachineEvent> wire, String id) throws Exception {
        wire.inject(id, new Dial(new LabFacts(id, System.currentTimeMillis(), Lab.BTCL_DOOR, 1, "8801"))).toCompletableFuture().get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        wire.inject(id, new Answer(System.currentTimeMillis())).toCompletableFuture().get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        wire.inject(id, new Hangup(System.currentTimeMillis())).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void twoLanesOnOneDatabase_writeTwoTables_eachItsOwnSessions_theFinalRowsKept() throws Exception {
        call(lane("AdSupervisor"), "ad-1");
        call(lane("CallSupervisor"), "call-1");

        assertThat(awaitRow("AdSupervisor", "ad-1", "SUCCEEDED")).isTrue();
        assertThat(awaitRow("CallSupervisor", "call-1", "SUCCEEDED")).isTrue();
        assertThat(rowsOf("AdSupervisor")).containsExactly("ad-1:SUCCEEDED");
        assertThat(rowsOf("CallSupervisor")).containsExactly("call-1:SUCCEEDED");
    }

    private boolean awaitRow(String table, String id, String state) throws Exception {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            if (rowsOf(table).contains(id + ":" + state)) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private List<String> rowsOf(String table) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = pool.getConnection(); ResultSet t = c.getMetaData().getTables(null, null, null, new String[] {"TABLE"})) {
            String found = null;
            while (t.next()) if (t.getString("TABLE_NAME").equalsIgnoreCase(table)) found = t.getString("TABLE_NAME");
            if (found == null) return rows;
            try (ResultSet r = c.createStatement().executeQuery("SELECT machine_id, current_state FROM \"" + found + "\"")) {
                while (r.next()) rows.add(r.getString(1) + ":" + r.getString(2));
            }
        }
        return rows;
    }
}
