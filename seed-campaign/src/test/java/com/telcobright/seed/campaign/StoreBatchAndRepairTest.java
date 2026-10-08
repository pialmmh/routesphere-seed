package com.telcobright.seed.campaign;

import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.seed.campaign.jdbc.Dialect;
import com.telcobright.seed.campaign.jdbc.JdbcCampaignStore;
import com.telcobright.seed.campaign.jdbc.JdbcCampaignStore.Counters;
import com.telcobright.seed.campaign.spi.CampaignStore;
import com.telcobright.seed.campaign.spi.StoreChange;
import com.telcobright.seed.campaign.spi.StoreRepair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ad-sphere ARCH-0043 R1-1, the store's part — in BOTH dialects (H2 in MySQL mode and in PostgreSQL mode) and for both homes of the
 * counters: a batch of changes is ONE transaction with ONE counter statement a campaign; a batch may be written twice and the rows end
 * right; a start closes what a dead process left and sets the counters from the task rows. {@link PostgresStoreBatchIT} runs the same
 * checks on a real PostgreSQL.
 */
class StoreBatchAndRepairTest {

    static final Instant T0 = Instant.parse("2026-10-04T06:00:00Z");

    static CampaignTask task(String id, String tenant, int campaign) {
        return new CampaignTask(id, tenant, campaign, 701, CampaignKind.AD, "aa:bb:cc:dd:ee:01", "c-77", "zone0", "site-1", "wifi-1", TaskState.PROCESSING,
            T0, null, null, 0, null, null, Map.of("viewSeconds", 15));
    }

    static CampaignTask ended(String id, String tenant, int campaign) {
        return task(id, tenant, campaign).completed(T0.plusSeconds(15), 15, new TaskCharge(9001L, "BDT", BigDecimal.ZERO, new BigDecimal("0.30"), "1001"), "CREDITED");
    }

    static List<StoreChange> claimed(String id, String tenant, int campaign) {
        return List.of(new StoreChange.TaskInserted(task(id, tenant, campaign)), new StoreChange.CountersBumped(tenant, campaign, 0, 0, +1));
    }

    static List<StoreChange> completed(String id, String tenant, int campaign) {
        return List.of(new StoreChange.TaskUpdated(ended(id, tenant, campaign)), new StoreChange.CountersBumped(tenant, campaign, +1, 0, -1));
    }

    /** The counters of a campaign where this store keeps them: {sent, failed, pending}. */
    static int[] counters(DataSource ds, Counters where, int campaignId) throws Exception {
        Map<String, Object> row = where == Counters.COUNTER_TABLE ? CampaignCounterTableTest.counterRow(ds, campaignId) : CampaignCounterTableTest.campaignRow(ds, campaignId);
        return new int[] {((Number) row.get("sent_task_count")).intValue(), ((Number) row.get("failed_task_count")).intValue(), ((Number) row.get("pending_task_count")).intValue()};
    }

    static int rows(DataSource ds, String where) throws Exception {
        return CampaignCounterTableTest.count(ds, "SELECT COUNT(*) FROM campaign_task" + (where.isEmpty() ? "" : " WHERE " + where));
    }

    // ── a batch is ONE transaction ───────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a_batch_is_one_transaction_all_of_it_or_none(Dialect d) throws Exception {
        for (Counters where : Counters.values()) aBatchIsAllOrNothing(CampaignCounterTableTest.open(d), d, where);
    }

    static void aBatchIsAllOrNothing(DataSource ds, Dialect d, Counters where) throws Exception {
        JdbcCampaignStore store = CampaignCounterTableTest.store(ds, d, where);
        int[] before = where == Counters.CAMPAIGN_ROW ? counters(ds, where, 42) : new int[3];
        List<StoreChange> batch = new ArrayList<>();
        batch.addAll(claimed("v1", "wroot", 42));
        batch.addAll(claimed("v2", "wroot", 42));
        batch.addAll(completed("v1", "wroot", 42));
        batch.add(new StoreChange.TaskInserted(task("an-id-that-is-much-longer-than-the-fifty-characters-the-column-holds", "wroot", 42)));

        assertThatThrownBy(() -> store.write(batch)).hasMessageContaining("characters");

        assertThat(rows(ds, "")).as("the batch failed at its last change: NOTHING of it is in the store").isZero();
        if (where == Counters.COUNTER_TABLE) assertThat(CampaignCounterTableTest.count(ds, "SELECT COUNT(*) FROM campaign_counter")).isZero();
        else assertThat(counters(ds, where, 42)).containsExactly(before);

        store.write(batch.subList(0, batch.size() - 1));                        // the same batch without the change the store refuses

        assertThat(rows(ds, "")).isEqualTo(2);
        assertThat(rows(ds, "uniqueId = 'v1' AND STATE = 11 AND STATUS = 11 AND BILLSEC = 15 AND idPackageAccount = 9001")).as("v1 ended: its row is final, with its charge").isEqualTo(1);
        assertThat(rows(ds, "uniqueId = 'v2' AND STATE = 1")).isEqualTo(1);
        assertThat(counters(ds, where, 42)).as("sent +1, pending +1 (two claimed, one ended)").containsExactly(before[0] + 1, before[1], before[2] + 1);
    }

    // ── per campaign ONE counter statement ───────────────────────────────────

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void two_thousand_ends_of_one_campaign_are_one_counter_statement_not_two_thousand(Dialect d) throws Exception {
        for (Counters where : Counters.values()) oneCounterStatementACampaign(CampaignCounterTableTest.open(d), d, where);
    }

    static void oneCounterStatementACampaign(DataSource ds, Dialect d, Counters where) throws Exception {
        Map<String, AtomicInteger> executed = new ConcurrentHashMap<>();
        AtomicInteger connections = new AtomicInteger();
        JdbcCampaignStore store = CampaignCounterTableTest.store(counting(ds, executed, connections), d, where);
        int[] before = where == Counters.CAMPAIGN_ROW ? counters(ds, where, 42) : new int[3];
        List<StoreChange> claims = new ArrayList<>(), ends = new ArrayList<>();
        for (int i = 0; i < 2000; i++) { claims.addAll(claimed("v" + i, "wroot", 42)); ends.addAll(completed("v" + i, "wroot", 42)); }
        ends.add(new StoreChange.CountersBumped("wroot", 43, 0, 1, 0));           // another campaign in the same batch: its own statement
        store.write(claims);
        executed.clear();
        connections.set(0);

        store.write(ends);

        assertThat(connections.get()).as("2,000 ends took ONE connection: a pool of four is never asked for more").isEqualTo(1);
        String counterTable = where == Counters.COUNTER_TABLE ? "campaign_counter" : "update campaign set sent_task_count";
        int counterStatements = executed.entrySet().stream().filter(e -> e.getKey().toLowerCase().contains(counterTable)).mapToInt(e -> e.getValue().get()).sum();
        assertThat(counterStatements).as("one counter statement for campaign 42 with the batch's sums, one for 43").isEqualTo(2);
        assertThat(counters(ds, where, 42)).as("sent 2,000 more, pending back to where it was").containsExactly(before[0] + 2000, before[1], before[2]);
        assertThat(rows(ds, "STATE = 11")).as("every task row is final").isEqualTo(2000);
        assertThat(rows(ds, "STATE = 1")).isZero();
    }

    // ── a batch may be written twice ─────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a_batch_written_twice_makes_no_second_row_and_an_update_that_finds_no_row_makes_it(Dialect d) throws Exception {
        writtenTwiceTheRowsEndRight(CampaignCounterTableTest.open(d), d);
    }

    static void writtenTwiceTheRowsEndRight(DataSource ds, Dialect d) throws Exception {
        JdbcCampaignStore store = CampaignCounterTableTest.store(ds, d, Counters.COUNTER_TABLE);
        List<StoreChange> life = new ArrayList<>(claimed("v1", "wroot", 42));
        life.addAll(completed("v1", "wroot", 42));

        store.write(life);
        store.write(life);                                                       // the commit was made and its answer was lost; a journal read again

        assertThat(rows(ds, "uniqueId = 'v1'")).as("one row, not two").isEqualTo(1);
        assertThat(rows(ds, "uniqueId = 'v1' AND STATE = 11")).as("the insert said again did not put the row back to PROCESSING").isEqualTo(1);

        store.write(completed("orphan", "wroot", 42));                           // an end whose claim never reached the store (a kill took the queue)

        assertThat(rows(ds, "uniqueId = 'orphan' AND STATE = 11 AND tenantName = 'wroot' AND CAMPAIGN_ID = 42 AND TASK_TYPE = 'AD' AND PHONE_NUMBER = 'aa:bb:cc:dd:ee:01'"))
            .as("the update is the whole truth of the task: the row is made, final").isEqualTo(1);
    }

    // ── a start repairs what a dead process left ─────────────────────────────

    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a_start_closes_what_a_dead_process_left_and_sets_the_counters_from_the_task_rows(Dialect d) throws Exception {
        for (Counters where : Counters.values()) theStartRepairs(CampaignCounterTableTest.open(d), d, where);
    }

    static void theStartRepairs(DataSource ds, Dialect d, Counters where) throws Exception {
        JdbcCampaignStore store = CampaignCounterTableTest.store(ds, d, where);
        // what a process that was killed left: five views of wroot ended in memory, two of them reached the store; one view of another
        // served tenant of the same database (res_44) is LIVE in another writer; the counters are what the lost bumps left them
        for (String id : List.of("w1", "w2", "w3", "w4", "w5")) store.insertTask(task(id, "wroot", 42));
        store.updateTask(ended("w1", "wroot", 42));
        store.updateTask(ended("w2", "wroot", 42));
        store.updateTask(task("w3", "wroot", 42).failed(T0.plusSeconds(3), 3, "STALLED"));
        store.insertTask(task("r1", "res_44", 42));
        store.bumpCounters("wroot", 42, 0, 0, +875);                             // a pending that never came down
        try (Connection c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("INSERT INTO campaign_task (uniqueId, CAMPAIGN_ID, ID_PARTNER, PHONE_NUMBER, TASK_TYPE, STATE, CREATED_STAMP) VALUES ('sms-1', 44, 701, '8801', 'SMS', 1, CURRENT_TIMESTAMP)");
        }
        int[] before = counters(ds, where, 42);
        Instant start = T0.plusSeconds(600);

        StoreRepair repair = store.repairAfterRestart("wroot", CampaignStore.LOST_AT_RESTART, start);

        assertThat(repair.tasksClosed()).as("w4 and w5: not final, and no machine of this process holds them").isEqualTo(2);
        assertThat(rows(ds, "tenantName = 'wroot' AND STATE = 1")).as("no task of wroot is left PROCESSING").isZero();
        assertThat(rows(ds, "uniqueId IN ('w4', 'w5') AND STATE = 5 AND STATUS = 5 AND HANGUP_CAUSE = 'LOST_AT_RESTART' AND END_TIME_MILLIS = " + start.toEpochMilli())).isEqualTo(2);
        assertThat(rows(ds, "uniqueId = 'w3' AND HANGUP_CAUSE = 'STALLED'")).as("a task that was final keeps its own end").isEqualTo(1);
        assertThat(rows(ds, "uniqueId = 'r1' AND STATE = 1")).as("another tenant's live view is not this start's to close").isEqualTo(1);
        assertThat(rows(ds, "uniqueId = 'sms-1' AND STATE = 1")).as("another kind's task is not this store's").isEqualTo(1);

        int[] now = counters(ds, where, 42);
        if (where == Counters.COUNTER_TABLE) {
            assertThat(now).as("the counter is this store's own traffic: SET to the count of the rows — sent 2, failed 3 (w3, w4, w5), pending 1 (r1)").containsExactly(2, 3, 1);
        } else {
            assertThat(now).as("the campaign's own row, which others write too (its 12 sent have no task row here): sent and failed are only raised, pending is the rows not final")
                .containsExactly(Math.max(before[0], 2), Math.max(before[1], 3), 1);
        }
        assertThat(repair.countersCorrected()).isEqualTo(1);
        assertThat(repair.words()).contains("2 task(s) of wroot").contains("LOST_AT_RESTART").contains("campaign 42").contains("→ " + now[0] + "/" + now[1] + "/" + now[2]);

        StoreRepair again = store.repairAfterRestart("wroot", CampaignStore.LOST_AT_RESTART, start.plusSeconds(1));
        assertThat(again.nothing()).as("a store that is right is left alone").isTrue();
        assertThat(again.words()).isEmpty();
    }

    // ── ARCH-0067 item 2: the repair seeks the index — STATE IN the open states, derived from TaskState ──

    /** One task in EVERY state of the enum: the start closes exactly the non-terminal ones and the counters count them as the rows say. */
    @ParameterizedTest
    @EnumSource(Dialect.class)
    void a_start_closes_every_non_terminal_state_of_the_enum_and_leaves_every_terminal_one(Dialect d) throws Exception {
        for (Counters where : Counters.values()) everyStateOfTheEnum(CampaignCounterTableTest.open(d), d, where);
    }

    static void everyStateOfTheEnum(DataSource ds, Dialect d, Counters where) throws Exception {
        JdbcCampaignStore store = CampaignCounterTableTest.store(ds, d, where);
        for (TaskState state : TaskState.values()) {
            store.insertTask(task("s-" + state.code(), "wroot", 42));
            try (Connection c = ds.getConnection(); var st = c.createStatement()) {
                st.execute("UPDATE campaign_task SET STATE = " + state.code() + " WHERE uniqueId = 's-" + state.code() + "'");
            }
        }
        int[] before = where == Counters.CAMPAIGN_ROW ? counters(ds, where, 42) : new int[3];     // the counter table has no row before the repair makes it
        Instant start = T0.plusSeconds(600);

        StoreRepair repair = store.repairAfterRestart("wroot", CampaignStore.LOST_AT_RESTART, start);

        List<TaskState> open = Arrays.stream(TaskState.values()).filter(s -> !s.terminal()).toList();
        assertThat(repair.tasksClosed()).as("every non-terminal state closed: " + open).isEqualTo(open.size());
        for (TaskState state : TaskState.values()) {
            int closed = rows(ds, "uniqueId = 's-" + state.code() + "' AND STATE = " + TaskState.FAILED.code() + " AND HANGUP_CAUSE = 'LOST_AT_RESTART'");
            assertThat(closed).as(state + (state.terminal() ? " is terminal: left as it is" : " is open: closed LOST_AT_RESTART")).isEqualTo(state.terminal() ? 0 : 1);
        }
        int[] now = counters(ds, where, 42);
        assertThat(now[2]).as("pending = the rows not final: none").isZero();
        if (where == Counters.COUNTER_TABLE) {
            assertThat(now).as("the counter table: SET from the rows — sent 1 (SENT's own), failed 1 + the closed ones").containsExactly(1, 1 + open.size(), 0);
        } else {
            assertThat(now).as("the campaign's own row (others write it too): sent and failed only raised").containsExactly(Math.max(before[0], 1), Math.max(before[1], 1 + open.size()), 0);
        }
    }

    // ── a data source that counts ────────────────────────────────────────────

    /** The same data source, counting the connections taken and how often each statement's text was executed. */
    static DataSource counting(DataSource ds, Map<String, AtomicInteger> executed, AtomicInteger connections) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
            Object result = invoke(ds, method, args);
            if (!method.getName().equals("getConnection")) return result;
            connections.incrementAndGet();
            Connection real = (Connection) result;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (p, m, a) -> {
                Object made = invoke(real, m, a);
                if (!m.getName().equals("prepareStatement")) return made;
                String sql = (String) a[0];
                PreparedStatement statement = (PreparedStatement) made;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (ps, pm, pa) -> {
                    if (pm.getName().startsWith("execute")) executed.computeIfAbsent(sql, s -> new AtomicInteger()).incrementAndGet();
                    return invoke(statement, pm, pa);
                });
            });
        });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
