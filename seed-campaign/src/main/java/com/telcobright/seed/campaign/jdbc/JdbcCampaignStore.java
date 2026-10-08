package com.telcobright.seed.campaign.jdbc;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.seed.campaign.spi.CampaignStore;
import com.telcobright.seed.campaign.spi.StoreChange;
import com.telcobright.seed.campaign.spi.StoreRepair;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * routesphere's schema on JDBC (MySQL; H2 in MySQL mode for tests). One store = one tenant database = one campaign
 * kind. Task rows are written in the SMS/voice columns with the meaning the design gives them ({@code PHONE_NUMBER}
 * = subject, {@code MESSAGE} = creative id, the voice millis = the view's lifecycle, the billing columns = the charge).
 * The detail map is serialised by the product-supplied {@code json} function (this module has no JSON library).
 *
 * <p>Where a campaign's three counters live is the store's {@link Counters}: on the campaign's own row (the default, the shape the
 * switch has always had), or in {@code campaign_counter}, a traffic table of their own — for a product whose {@code campaign} table is
 * configuration that a change feed publishes, where a view must not write it.
 */
public final class JdbcCampaignStore implements CampaignStore {

    /** Where {@code SENT_TASK_COUNT}, {@code FAILED_TASK_COUNT} and {@code PENDING_TASK_COUNT} live. */
    public enum Counters {
        /** On the campaign's own row: {@code bumpCounters} is an UPDATE of {@code campaign}. The default; nothing changes for a caller. */
        CAMPAIGN_ROW,
        /**
         * In {@code campaign_counter} ({@link CampaignSchema#counterTable}): {@code bumpCounters} is ONE upsert of that table and the
         * campaign's row is not written; {@link #campaigns} reads the counters from it (no row = zeros). {@code markComplete} stays on the
         * campaign's row: a campaign ending is a configuration change, and rare.
         */
        COUNTER_TABLE
    }

    private static final String INSERT_TASK = """
        INSERT INTO campaign_task (uniqueId, CAMPAIGN_ID, ID_PARTNER, PHONE_NUMBER, MESSAGE, TASK_TYPE, STATE, STATUS,
          RETRY_COUNT, CREATED_STAMP, LAST_UPDATED_STAMP, START_TIME_MILLIS, TERMINATING_CALLED_NUMBER,
          ORIGINATING_CALLING_NUMBER, CLIENT_TRANS_ID, tenantName, TASK_DETAIL_JSON)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)""";
    /**
     * The same row, made only when no row has that id — for a batch, which may be written again whole (a batch that failed, a journal
     * line of a process that stopped): a task that is there already is left as it is. The id is said twice.
     */
    private static final String INSERT_TASK_IF_ABSENT_SELECT = """
        INSERT INTO campaign_task (uniqueId, CAMPAIGN_ID, ID_PARTNER, PHONE_NUMBER, MESSAGE, TASK_TYPE, STATE, STATUS,
          RETRY_COUNT, CREATED_STAMP, LAST_UPDATED_STAMP, START_TIME_MILLIS, TERMINATING_CALLED_NUMBER,
          ORIGINATING_CALLING_NUMBER, CLIENT_TRANS_ID, tenantName, TASK_DETAIL_JSON)
        SELECT ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?""";
    private static final String WHERE_NO_SUCH_TASK = " WHERE NOT EXISTS (SELECT 1 FROM campaign_task WHERE uniqueId = ?)";
    private static final String UPDATE_TASK = """
        UPDATE campaign_task SET STATE = ?, STATUS = ?, LAST_UPDATED_STAMP = ?, ANSWERED = ?, ANSWER_TIME_MILLIS = ?,
          END_TIME_MILLIS = ?, BILLSEC = ?, HANGUP_CAUSE = ?, idPackageAccount = ?, packageAmount = ?, uom = ?,
          inPartnerCost = ?, isPrepaid = ?, MatchedPrefixCustomer = ?, TASK_DETAIL_JSON = ?
        WHERE uniqueId = ?""";
    private static final String BUMP = """
        UPDATE campaign SET SENT_TASK_COUNT = COALESCE(SENT_TASK_COUNT, 0) + ?, FAILED_TASK_COUNT = COALESCE(FAILED_TASK_COUNT, 0) + ?,
          PENDING_TASK_COUNT = GREATEST(COALESCE(PENDING_TASK_COUNT, 0) + ?, 0), LAST_UPDATED_STAMP = ? WHERE CAMPAIGN_ID = ?""";
    private static final String COMPLETE = "UPDATE campaign SET STATUS = ?, LAST_UPDATED_STAMP = ? WHERE CAMPAIGN_ID = ?";

    // ── the counters in their own table: ONE upsert per bump, each engine's own wording ──
    // Parameters, the same six in each: the campaign, the sent, failed and pending deltas, the stamp, and the pending delta once more
    // (the row's pending never falls below 0, as on the campaign's row: the first row keeps GREATEST(delta, 0), a later bump adds the delta itself).
    private static final String UPSERT_COLUMNS = "(CAMPAIGN_ID, SENT_TASK_COUNT, FAILED_TASK_COUNT, PENDING_TASK_COUNT, LAST_UPDATED_STAMP)";
    private static final String UPSERT_POSTGRES = "INSERT INTO campaign_counter " + UPSERT_COLUMNS + " VALUES (?, ?, ?, GREATEST(?, 0), ?)"
        + " ON CONFLICT (CAMPAIGN_ID) DO UPDATE SET SENT_TASK_COUNT = campaign_counter.SENT_TASK_COUNT + EXCLUDED.SENT_TASK_COUNT,"
        + " FAILED_TASK_COUNT = campaign_counter.FAILED_TASK_COUNT + EXCLUDED.FAILED_TASK_COUNT,"
        + " PENDING_TASK_COUNT = GREATEST(campaign_counter.PENDING_TASK_COUNT + ?, 0), LAST_UPDATED_STAMP = EXCLUDED.LAST_UPDATED_STAMP";
    // VALUES(col) on purpose: the tenants' servers are MySQL / Percona 5.7, which has no row alias (AS new); 8.0.20+ only warns.
    private static final String UPSERT_MYSQL = "INSERT INTO campaign_counter " + UPSERT_COLUMNS + " VALUES (?, ?, ?, GREATEST(?, 0), ?)"
        + " ON DUPLICATE KEY UPDATE SENT_TASK_COUNT = SENT_TASK_COUNT + VALUES(SENT_TASK_COUNT),"
        + " FAILED_TASK_COUNT = FAILED_TASK_COUNT + VALUES(FAILED_TASK_COUNT),"
        + " PENDING_TASK_COUNT = GREATEST(PENDING_TASK_COUNT + ?, 0), LAST_UPDATED_STAMP = VALUES(LAST_UPDATED_STAMP)";
    /**
     * H2 in its PostgreSQL mode (the tests' engine, a PC's) has no {@code ON CONFLICT … DO UPDATE}: it takes the standard's MERGE. A real
     * PostgreSQL keeps {@code ON CONFLICT}: a MERGE does not settle two first bumps of one campaign that race (the loser fails on the key —
     * on H2 the bump is then said once more, see {@code bumpTheCounterTable}), {@code ON CONFLICT} does.
     */
    private static final String UPSERT_H2_IN_POSTGRES_MODE = "MERGE INTO campaign_counter t USING (VALUES (CAST(? AS INT), CAST(? AS INT), CAST(? AS INT),"
        + " CAST(? AS INT), CAST(? AS TIMESTAMP), CAST(? AS INT))) s (id, sent, failed, pending, at, pending_again) ON t.CAMPAIGN_ID = s.id"
        + " WHEN MATCHED THEN UPDATE SET SENT_TASK_COUNT = t.SENT_TASK_COUNT + s.sent, FAILED_TASK_COUNT = t.FAILED_TASK_COUNT + s.failed,"
        + " PENDING_TASK_COUNT = GREATEST(t.PENDING_TASK_COUNT + s.pending_again, 0), LAST_UPDATED_STAMP = s.at"
        + " WHEN NOT MATCHED THEN INSERT " + UPSERT_COLUMNS + " VALUES (s.id, s.sent, s.failed, GREATEST(s.pending, 0), s.at)";
    private static final String COUNTERS_OF = "SELECT CAMPAIGN_ID, SENT_TASK_COUNT, FAILED_TASK_COUNT, PENDING_TASK_COUNT FROM campaign_counter";
    /**
     * SQLSTATE of a unique-key violation on H2 and PostgreSQL. (MySQL says 23000 — and never needs it here: its upsert, like
     * PostgreSQL's, settles two first bumps that race; only H2's MERGE can lose that race.)
     */
    private static final String UNIQUE_VIOLATION = "23505";

    /** {@code enumjobstatus} ids that a task's STATUS mirrors (the SMS runner writes the same ones). */
    static final int STATUS_COMPLETE = 1, STATUS_FAILED = 5, STATUS_SENT = 11, STATUS_PROCESSING = 15;

    private static final Logger log = LoggerFactory.getLogger(JdbcCampaignStore.class);

    private final AtomicLong cuts = new AtomicLong();
    private final DataSource ds;
    private final CampaignKind kind;
    private final Function<Map<String, Object>, String> json;
    private final Dialect dialect;
    private final CampaignRowReader reader;
    private final ZoneId zone;
    private final Counters counters;
    /** The upsert of this store's engine, found at the first bump (COUNTER_TABLE only). */
    private volatile String upsert;

    /** The dialect read from the pool's URL ({@code jdbc:postgresql:} → PostgreSQL, else MySQL). */
    public JdbcCampaignStore(DataSource ds, CampaignKind kind, Function<Map<String, Object>, String> json) {
        this(ds, kind, json, Dialect.of(ds));
    }

    public JdbcCampaignStore(DataSource ds, CampaignKind kind, Function<Map<String, Object>, String> json, Dialect dialect) {
        this(ds, kind, json, dialect, null);
    }

    /**
     * The JVM's zone as the wall clock — the shape before the zone was a parameter. It is right only while the JVM runs in the
     * tenant's zone (a container runs in UTC), so it says so once, WARN; pass the tenant's zone instead.
     *
     * @param creativesSql the product's own creatives query (see {@code CampaignRowReader}); null = the legacy {@code campaign_creative} rows
     */
    public JdbcCampaignStore(DataSource ds, CampaignKind kind, Function<Map<String, Object>, String> json, Dialect dialect, String creativesSql) {
        this(ds, kind, json, dialect, creativesSql, ZoneId.systemDefault());
        if (JVM_ZONE_SAID.compareAndSet(false, true)) {
            log.warn("campaign store stamps its rows in the JVM's zone {} — right only while the JVM runs in the tenant's zone; pass the tenant's zone",
                zone);
        }
    }

    /** The JVM-zone warning is said once per process, not once per store (a tenant reload makes a new store). */
    private static final java.util.concurrent.atomic.AtomicBoolean JVM_ZONE_SAID = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * @param creativesSql the product's own creatives query (see {@code CampaignRowReader}); null = the legacy {@code campaign_creative} rows
     * @param zone         the tenant's wall clock: every TIMESTAMP column ({@code CREATED_STAMP}, {@code LAST_UPDATED_STAMP}, …) is written
     *                     as the local time of this zone, whatever zone the JVM runs in (the legacy switch wrote Dhaka's wall clock because
     *                     its JVM ran there). The epoch-millis columns are zone-free and unchanged
     */
    public JdbcCampaignStore(DataSource ds, CampaignKind kind, Function<Map<String, Object>, String> json, Dialect dialect, String creativesSql, ZoneId zone) {
        this(ds, kind, json, dialect, creativesSql, zone, Counters.CAMPAIGN_ROW);
    }

    /**
     * @param creativesSql the product's own creatives query (see {@code CampaignRowReader}); null = the legacy {@code campaign_creative} rows
     * @param zone         the tenant's wall clock, as above: {@code campaign_counter.LAST_UPDATED_STAMP} keeps it too
     * @param counters     where the campaigns' counters live; {@link Counters#COUNTER_TABLE} needs {@link CampaignSchema#counterTable} in the schema
     */
    public JdbcCampaignStore(DataSource ds, CampaignKind kind, Function<Map<String, Object>, String> json, Dialect dialect, String creativesSql, ZoneId zone,
                             Counters counters) {
        if (zone == null) throw new IllegalArgumentException("the tenant's zone is required");
        if (counters == null) throw new IllegalArgumentException("where the counters live is required");
        this.ds = ds;
        this.kind = kind;
        this.json = json;
        this.dialect = dialect;
        this.counters = counters;
        this.reader = new CampaignRowReader(dialect, creativesSql, counters == Counters.COUNTER_TABLE);
        this.zone = zone;
    }

    /** The zone the TIMESTAMP columns are written in. */
    public ZoneId zone() { return zone; }

    /** Where this store keeps the campaigns' counters. */
    public Counters counters() { return counters; }

    /** An instant as the tenant's wall clock, the shape a TIMESTAMP (without zone) column keeps; never converted by the JVM's zone. */
    private LocalDateTime stamp(Instant at) { return LocalDateTime.ofInstant(at, zone); }

    public Dialect dialect() { return dialect; }

    @Override
    public List<Campaign> campaigns(String tenantId) {
        try (Connection c = ds.getConnection()) {
            return reader.read(c, tenantId, kind);
        } catch (SQLException e) {
            throw new IllegalStateException("campaigns of " + tenantId + " could not be read: " + e.getMessage(), e);
        }
    }

    @Override
    public void insertTask(CampaignTask t) {
        requireIdFits(t);
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT_TASK)) {
            bindInsert(ps, t);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("task " + t.uniqueId() + " could not be inserted: " + e.getMessage(), e);
        }
    }

    /** The insert's sixteen values, in the statement's order; the next free index is handed back. */
    private int bindInsert(PreparedStatement ps, CampaignTask t) throws SQLException {
        int i = 1;
        ps.setString(i++, t.uniqueId());
        ps.setInt(i++, t.campaignId());
        ps.setInt(i++, t.partnerId());
        ps.setString(i++, fit(t.subject(), CampaignSchema.TASK_NUMBER_WIDTH, "PHONE_NUMBER", t));
        ps.setString(i++, fit(t.creativeId(), CampaignSchema.TASK_MESSAGE_WIDTH, "MESSAGE", t));
        ps.setString(i++, t.kind().name());
        ps.setInt(i++, t.state().code());
        ps.setInt(i++, STATUS_PROCESSING);
        ps.setObject(i++, stamp(t.createdAt()));
        ps.setObject(i++, stamp(t.createdAt()));
        ps.setLong(i++, t.createdAt().toEpochMilli());
        ps.setString(i++, fit(t.zone(), CampaignSchema.TASK_NUMBER_WIDTH, "TERMINATING_CALLED_NUMBER", t));
        ps.setString(i++, fit(t.site(), CampaignSchema.TASK_NUMBER_WIDTH, "ORIGINATING_CALLING_NUMBER", t));
        ps.setString(i++, fit(t.clientRef(), CampaignSchema.TASK_CLIENT_REF_WIDTH, "CLIENT_TRANS_ID", t));
        ps.setString(i++, fit(t.tenantId(), CampaignSchema.TASK_TENANT_WIDTH, "tenantName", t));
        ps.setString(i++, json.apply(t.detail()));
        return i;
    }

    // ── a text longer than its column never costs a task its row ────────────

    /** The id is the row's key: it is never cut. An id that cannot fit is the caller's defect, said by name before any SQL. */
    private static void requireIdFits(CampaignTask t) {
        if (t.uniqueId() == null || t.uniqueId().length() <= CampaignSchema.TASK_ID_WIDTH) return;
        throw new IllegalStateException("task id '" + t.uniqueId() + "' is " + t.uniqueId().length() + " characters; campaign_task.uniqueId holds "
            + CampaignSchema.TASK_ID_WIDTH + " — the caller must mint ids that fit");
    }

    /**
     * The text as the column can hold it: cut to the column's width when longer (never in the middle of a character
     * pair), and said in the log for the first cut and every thousandth. The whole text stays in the caller's own records.
     */
    private String fit(String text, int width, String column, CampaignTask t) {
        if (text == null || text.length() <= width) return text;
        long soFar = cuts.incrementAndGet();
        if (soFar == 1 || soFar % 1000 == 0) {
            log.warn("campaign_task.{}: a text of {} characters was cut to the column's {} (task {}; {} cut so far)", column, text.length(), width, t.uniqueId(), soFar);
        }
        int end = Character.isHighSurrogate(text.charAt(width - 1)) ? width - 1 : width;
        return text.substring(0, end);
    }

    @Override
    public void updateTask(CampaignTask t) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(UPDATE_TASK)) {
            bindUpdate(ps, t);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("task " + t.uniqueId() + " could not be updated: " + e.getMessage(), e);
        }
    }

    private void bindUpdate(PreparedStatement ps, CampaignTask t) throws SQLException {
        TaskCharge ch = t.charge() == null ? TaskCharge.FREE : t.charge();
        int i = 1;
        ps.setInt(i++, t.state().code());
        ps.setInt(i++, statusOf(t));
        ps.setObject(i++, stamp(Instant.now()));
        ps.setInt(i++, t.answered() ? 1 : 0);
        setLong(ps, i++, t.answeredAt() == null ? null : t.answeredAt().toEpochMilli());
        setLong(ps, i++, t.endedAt() == null ? null : t.endedAt().toEpochMilli());
        ps.setInt(i++, t.billsec());
        ps.setString(i++, fit(t.endCause(), CampaignSchema.TASK_CAUSE_WIDTH, "HANGUP_CAUSE", t));
        setLong(ps, i++, ch.packageAccountId());
        ps.setDouble(i++, ch.packageAmount() == null ? 0 : ch.packageAmount().doubleValue());
        ps.setString(i++, fit(ch.uom(), CampaignSchema.TASK_UOM_WIDTH, "uom", t));
        ps.setDouble(i++, ch.cost() == null ? 0 : ch.cost().doubleValue());
        ps.setString(i++, ch.free() ? "0" : "1");
        ps.setString(i++, fit(ch.matchedPattern(), CampaignSchema.TASK_PREFIX_WIDTH, "MatchedPrefixCustomer", t));
        ps.setString(i++, json.apply(t.detail()));
        ps.setString(i, t.uniqueId());
    }

    // ── a batch: ONE transaction (the queue's writer; a journal's replay) ───

    /**
     * The batch in ONE transaction, on one connection: the task rows in their order, then for each campaign ONE counter statement with
     * the batch's sums, then the campaigns that reached their quota. All of it or none: a batch that failed wrote nothing, so it may be
     * written again whole. And a batch may be written TWICE (the commit was made and its answer was lost; a journal read again after a
     * crash): a task row that is there is not made again, and an update that finds no row makes it first — so the rows end right either
     * way. Only the counters would then count twice; a start sets them from the rows ({@link #repairAfterRestart}).
     */
    @Override
    public void write(List<StoreChange> batch) {
        if (batch.isEmpty()) return;
        try (Connection c = ds.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                writeTheTaskRows(c, batch);
                writeTheCounterSums(c, batch);
                writeTheCompletedCampaigns(c, batch);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException alreadyGone) { e.addSuppressed(alreadyGone); }
                throw e;
            } finally {
                try { c.setAutoCommit(autoCommit); } catch (SQLException alreadyGone) { /* the pool drops such a connection */ }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("a batch of " + batch.size() + " change(s) could not be written: " + e.getMessage(), e);
        }
    }

    /** Every insert and update of the batch, in the batch's order: a task's insert comes before its update. */
    private void writeTheTaskRows(Connection c, List<StoreChange> batch) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(insertIfAbsent(c)); PreparedStatement update = c.prepareStatement(UPDATE_TASK)) {
            for (StoreChange change : batch) {
                if (change instanceof StoreChange.TaskInserted made) insertIfAbsent(insert, made.task());
                else if (change instanceof StoreChange.TaskUpdated moved) updateOrMake(insert, update, moved.task());
            }
        }
    }

    private void insertIfAbsent(PreparedStatement insert, CampaignTask t) throws SQLException {
        requireIdFits(t);
        int next = bindInsert(insert, t);
        insert.setString(next, t.uniqueId());
        insert.executeUpdate();
    }

    /** The row as the task is now. A row that is not there (its insert was never written) is made first: the update is the whole truth. */
    private void updateOrMake(PreparedStatement insert, PreparedStatement update, CampaignTask t) throws SQLException {
        bindUpdate(update, t);
        if (update.executeUpdate() > 0) return;
        insertIfAbsent(insert, t);
        bindUpdate(update, t);
        update.executeUpdate();
    }

    /** MySQL wants a table in a SELECT that has a WHERE; PostgreSQL has no DUAL; H2 takes each engine's own wording in its mode. */
    private String insertIfAbsent(Connection c) {
        return INSERT_TASK_IF_ABSENT_SELECT + (dialect == Dialect.MYSQL ? " FROM DUAL" : "") + WHERE_NO_SUCH_TASK;
    }

    /** Per campaign ONE statement with the batch's sums: 2,000 views of one campaign that end together are one bump, not 2,000. */
    private void writeTheCounterSums(Connection c, List<StoreChange> batch) throws SQLException {
        Map<Integer, int[]> sums = new LinkedHashMap<>();
        for (StoreChange change : batch) {
            if (!(change instanceof StoreChange.CountersBumped bump)) continue;
            int[] sum = sums.computeIfAbsent(bump.campaignId(), id -> new int[3]);
            sum[0] += bump.sent();
            sum[1] += bump.failed();
            sum[2] += bump.pending();
        }
        if (sums.isEmpty()) return;
        boolean table = counters == Counters.COUNTER_TABLE;
        try (PreparedStatement ps = c.prepareStatement(table ? upsertOf(c) : BUMP)) {
            for (Map.Entry<Integer, int[]> campaign : sums.entrySet()) {
                int[] sum = campaign.getValue();
                if (sum[0] == 0 && sum[1] == 0 && sum[2] == 0) continue;
                if (table) bindUpsert(ps, campaign.getKey(), sum[0], sum[1], sum[2]);
                else bindBump(ps, campaign.getKey(), sum[0], sum[1], sum[2]);
                ps.executeUpdate();
            }
        }
    }

    private void writeTheCompletedCampaigns(Connection c, List<StoreChange> batch) throws SQLException {
        Set<Integer> completed = new LinkedHashSet<>();
        for (StoreChange change : batch) if (change instanceof StoreChange.CampaignCompleted done) completed.add(done.campaignId());
        if (completed.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(COMPLETE)) {
            for (int campaignId : completed) {
                ps.setInt(1, STATUS_COMPLETE);
                ps.setObject(2, stamp(Instant.now()));
                ps.setInt(3, campaignId);
                ps.executeUpdate();
            }
        }
    }

    @Override
    public void bumpCounters(String tenantId, int campaignId, int sentDelta, int failedDelta, int pendingDelta) {
        if (counters == Counters.COUNTER_TABLE) {
            bumpTheCounterTable(campaignId, sentDelta, failedDelta, pendingDelta);
            return;
        }
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(BUMP)) {
            bindBump(ps, campaignId, sentDelta, failedDelta, pendingDelta);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("counters of campaign " + campaignId + " could not be bumped: " + e.getMessage(), e);
        }
    }

    private void bindBump(PreparedStatement ps, int campaignId, int sentDelta, int failedDelta, int pendingDelta) throws SQLException {
        ps.setInt(1, sentDelta);
        ps.setInt(2, failedDelta);
        ps.setInt(3, pendingDelta);
        ps.setObject(4, stamp(Instant.now()));
        ps.setInt(5, campaignId);
    }

    private void bindUpsert(PreparedStatement ps, int campaignId, int sentDelta, int failedDelta, int pendingDelta) throws SQLException {
        ps.setInt(1, campaignId);
        ps.setInt(2, sentDelta);
        ps.setInt(3, failedDelta);
        ps.setInt(4, pendingDelta);
        ps.setObject(5, stamp(Instant.now()));
        ps.setInt(6, pendingDelta);
    }

    /** ONE upsert: the campaign's counter row is made by its first bump and added to by every later one; the campaign's row is not written. */
    private void bumpTheCounterTable(int campaignId, int sentDelta, int failedDelta, int pendingDelta) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(upsertOf(c))) {
            bindUpsert(ps, campaignId, sentDelta, failedDelta, pendingDelta);
            try {
                ps.executeUpdate();
            } catch (SQLException lostTheRace) {
                // two FIRST bumps of one campaign at once, on an engine whose upsert does not settle that race (H2's MERGE; PostgreSQL's
                // ON CONFLICT and MySQL's ON DUPLICATE KEY do): the loser's statement did nothing — the row is there now, said again it adds to it
                if (!UNIQUE_VIOLATION.equals(lostTheRace.getSQLState())) throw lostTheRace;
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("counters of campaign " + campaignId + " could not be bumped in " + CampaignSchema.COUNTER_TABLE + ": " + e.getMessage(), e);
        }
    }

    /** The engine's upsert: MySQL's, PostgreSQL's, or — the dialect is PostgreSQL's but the engine is H2 — the standard's MERGE. */
    private String upsertOf(Connection c) throws SQLException {
        String sql = upsert;
        if (sql != null) return sql;
        if (dialect == Dialect.MYSQL) sql = UPSERT_MYSQL;
        else sql = "H2".equalsIgnoreCase(c.getMetaData().getDatabaseProductName()) ? UPSERT_H2_IN_POSTGRES_MODE : UPSERT_POSTGRES;
        upsert = sql;
        return sql;
    }

    /**
     * The campaigns as given, their three counters taken from {@code campaign_counter} (no row = zeros) — for a product in
     * {@link Counters#COUNTER_TABLE} mode whose campaign list comes from elsewhere than {@link #campaigns} (a configuration tree read from
     * the campaigns' rows, whose own counters no view moves): overlay at every load, so a campaign's quota and counts carry across a
     * reload and a restart. With {@link Counters#CAMPAIGN_ROW} the campaigns come back as they are: their counts are the rows'.
     */
    public List<Campaign> withStoredCounters(List<Campaign> campaigns) {
        if (counters != Counters.COUNTER_TABLE || campaigns.isEmpty()) return campaigns;
        Map<Integer, int[]> stored = new HashMap<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(COUNTERS_OF); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) stored.put(rs.getInt(1), new int[] {rs.getInt(2), rs.getInt(3), rs.getInt(4)});
        } catch (SQLException e) {
            throw new IllegalStateException("the campaigns' counters could not be read from " + CampaignSchema.COUNTER_TABLE + ": " + e.getMessage(), e);
        }
        List<Campaign> out = new ArrayList<>(campaigns.size());
        for (Campaign campaign : campaigns) {
            int[] k = stored.get(campaign.id());
            out.add(k == null ? campaign.withCounters(0, 0, 0) : campaign.withCounters(k[0], k[1], k[2]));
        }
        return out;
    }

    // ── a start repairs what a dead process left ────────────────────────────

    /**
     * ARCH-0067 item 2 (prime-context PC-0014 §8 #4): the start's repair names the OPEN states — every {@link TaskState} whose
     * {@code terminal()} is false — as an {@code IN} list, so the index of W42 ({@code ix_campaign_task_repair_at_start}: TASK_TYPE, STATE,
     * CAMPAIGN_ID) is sought; a {@code NOT IN} cannot seek it. The list is DERIVED from the enum, in its order, never written by hand: a
     * state added later is in it the day it is added ({@code RepairSeeksTheIndexTest} and {@code StoreBatchAndRepairTest} hold it to the enum).
     */
    static final List<Integer> OPEN_STATE_CODES = Arrays.stream(TaskState.values()).filter(s -> !s.terminal()).map(TaskState::code).toList();
    private static final String OPEN_STATES_IN = "STATE IN (" + String.join(", ", Collections.nCopies(OPEN_STATE_CODES.size(), "?")) + ")";
    static final String CLOSE_WHAT_IS_NOT_FINAL = "UPDATE campaign_task SET STATE = ?, STATUS = ?, LAST_UPDATED_STAMP = ?, END_TIME_MILLIS = ?, HANGUP_CAUSE = ?"
        + " WHERE TASK_TYPE = ? AND tenantName = ? AND " + OPEN_STATES_IN;
    static final String COUNT_THE_TASKS = "SELECT CAMPAIGN_ID, SUM(CASE WHEN STATE = ? THEN 1 ELSE 0 END), SUM(CASE WHEN STATE = ? THEN 1 ELSE 0 END),"
        + " SUM(CASE WHEN " + OPEN_STATES_IN + " THEN 1 ELSE 0 END)"
        + " FROM campaign_task WHERE TASK_TYPE = ? GROUP BY CAMPAIGN_ID";
    private static final String COUNTERS_ON_THE_ROWS = "SELECT CAMPAIGN_ID, COALESCE(SENT_TASK_COUNT, 0), COALESCE(FAILED_TASK_COUNT, 0),"
        + " COALESCE(PENDING_TASK_COUNT, 0) FROM campaign WHERE CAMPAIGN_TYPE = ? FOR UPDATE";
    private static final String SET_ON_THE_ROW = "UPDATE campaign SET SENT_TASK_COUNT = ?, FAILED_TASK_COUNT = ?, PENDING_TASK_COUNT = ?,"
        + " LAST_UPDATED_STAMP = ? WHERE CAMPAIGN_ID = ?";
    private static final String SET_IN_THE_TABLE = "UPDATE campaign_counter SET SENT_TASK_COUNT = ?, FAILED_TASK_COUNT = ?, PENDING_TASK_COUNT = ?,"
        + " LAST_UPDATED_STAMP = ? WHERE CAMPAIGN_ID = ?";
    private static final String MAKE_IN_THE_TABLE = "INSERT INTO campaign_counter (SENT_TASK_COUNT, FAILED_TASK_COUNT, PENDING_TASK_COUNT,"
        + " LAST_UPDATED_STAMP, CAMPAIGN_ID) VALUES (?, ?, ?, ?, ?)";

    /**
     * One transaction: every task of this store's kind and of {@code tenantName} that is not final is closed FAILED with {@code cause};
     * then the campaigns' counters are made to say what the task rows say. The counter rows are locked first, so a writer of the same
     * tables that is live (another served tenant of the same database) waits and then adds to what was set.
     *
     * <p><b>What "set" means</b> depends on whose the counter is. In {@link Counters#COUNTER_TABLE} the counters are this store's own
     * traffic: SENT, FAILED and PENDING are SET to the count of the rows. In {@link Counters#CAMPAIGN_ROW} the counters stand on the
     * campaign's own row, which others write too (a migration, another runner whose task rows are not kept for ever): SENT and FAILED
     * are only RAISED to the count — never lowered — and PENDING is set to the rows that are not final.
     */
    @Override
    public StoreRepair repairAfterRestart(String tenantName, String cause, Instant at) {
        try (Connection c = ds.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                Map<Integer, int[]> stored = lockTheCounters(c);
                int closed = closeWhatIsNotFinal(c, tenantName, cause, at);
                List<String> corrected = setTheCountersFromTheRows(c, stored, countTheTasks(c));
                c.commit();
                return new StoreRepair(closed, corrected.size(), wordsOf(tenantName, closed, cause, corrected));
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException alreadyGone) { e.addSuppressed(alreadyGone); }
                throw e;
            } finally {
                try { c.setAutoCommit(autoCommit); } catch (SQLException alreadyGone) { /* the pool drops such a connection */ }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the tasks of " + tenantName + " could not be repaired at the start: " + e.getMessage(), e);
        }
    }

    /** Campaign → {sent, failed, pending} as stored now, the rows locked until the repair commits. */
    private Map<Integer, int[]> lockTheCounters(Connection c) throws SQLException {
        Map<Integer, int[]> stored = new HashMap<>();
        String sql = counters == Counters.COUNTER_TABLE ? COUNTERS_OF + " FOR UPDATE" : COUNTERS_ON_THE_ROWS;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (counters == Counters.CAMPAIGN_ROW) ps.setString(1, kind.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) stored.put(rs.getInt(1), new int[] {rs.getInt(2), rs.getInt(3), rs.getInt(4)});
            }
        }
        return stored;
    }

    private int closeWhatIsNotFinal(Connection c, String tenantName, String cause, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(CLOSE_WHAT_IS_NOT_FINAL)) {
            ps.setInt(1, TaskState.FAILED.code());
            ps.setInt(2, STATUS_FAILED);
            ps.setObject(3, stamp(at));
            ps.setLong(4, at.toEpochMilli());
            ps.setString(5, cause);
            ps.setString(6, kind.name());
            ps.setString(7, tenantName);
            bindTheOpenStates(ps, 8);
            return ps.executeUpdate();
        }
    }

    /** The open states' codes bound from {@code first} on; the next free index. */
    private static int bindTheOpenStates(PreparedStatement ps, int first) throws SQLException {
        int i = first;
        for (int code : OPEN_STATE_CODES) ps.setInt(i++, code);
        return i;
    }

    /** Campaign → {sent, failed, not final} as the task rows of this kind say (every tenant's rows: a campaign's counter is one). */
    private Map<Integer, int[]> countTheTasks(Connection c) throws SQLException {
        Map<Integer, int[]> counted = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(COUNT_THE_TASKS)) {
            ps.setInt(1, TaskState.SENT.code());
            ps.setInt(2, TaskState.FAILED.code());
            int next = bindTheOpenStates(ps, 3);
            ps.setString(next, kind.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) counted.put(rs.getInt(1), new int[] {rs.getInt(2), rs.getInt(3), rs.getInt(4)});
            }
        }
        return counted;
    }

    private List<String> setTheCountersFromTheRows(Connection c, Map<Integer, int[]> stored, Map<Integer, int[]> counted) throws SQLException {
        boolean table = counters == Counters.COUNTER_TABLE;
        List<String> corrected = new ArrayList<>();
        java.util.TreeSet<Integer> campaigns = new java.util.TreeSet<>(stored.keySet());
        if (table) campaigns.addAll(counted.keySet());           // a campaign with tasks and no counter row yet gets its row
        try (PreparedStatement set = c.prepareStatement(table ? SET_IN_THE_TABLE : SET_ON_THE_ROW);
             PreparedStatement make = table ? c.prepareStatement(MAKE_IN_THE_TABLE) : null) {
            for (int campaignId : campaigns) {
                int[] was = stored.get(campaignId);
                int[] rows = counted.getOrDefault(campaignId, new int[3]);
                int[] now = table ? rows : new int[] {Math.max(was[0], rows[0]), Math.max(was[1], rows[1]), rows[2]};
                if (was != null && was[0] == now[0] && was[1] == now[1] && was[2] == now[2]) continue;
                PreparedStatement ps = was == null ? make : set;
                ps.setInt(1, now[0]);
                ps.setInt(2, now[1]);
                ps.setInt(3, now[2]);
                ps.setObject(4, stamp(Instant.now()));
                ps.setInt(5, campaignId);
                ps.executeUpdate();
                corrected.add("campaign " + campaignId + ": sent/failed/pending " + (was == null ? "(no row)" : was[0] + "/" + was[1] + "/" + was[2])
                    + " → " + now[0] + "/" + now[1] + "/" + now[2]);
            }
        }
        return corrected;
    }

    private static String wordsOf(String tenantName, int closed, String cause, List<String> corrected) {
        if (closed == 0 && corrected.isEmpty()) return "";
        return closed + " task(s) of " + tenantName + " that a stopped process left not final are closed " + cause + "; " + corrected.size()
            + " campaign(s) had counters that did not say what their task rows say, set now" + (corrected.isEmpty() ? "" : " — " + String.join("; ", corrected));
    }

    @Override
    public void markComplete(String tenantId, int campaignId) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(COMPLETE)) {
            ps.setInt(1, STATUS_COMPLETE);
            ps.setObject(2, stamp(Instant.now()));
            ps.setInt(3, campaignId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("campaign " + campaignId + " could not be marked Complete: " + e.getMessage(), e);
        }
    }

    private static int statusOf(CampaignTask t) {
        return switch (t.state()) {
            case SENT -> STATUS_SENT;
            case FAILED -> STATUS_FAILED;
            default -> STATUS_PROCESSING;
        };
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) ps.setNull(i, Types.BIGINT); else ps.setLong(i, v);
    }
}
