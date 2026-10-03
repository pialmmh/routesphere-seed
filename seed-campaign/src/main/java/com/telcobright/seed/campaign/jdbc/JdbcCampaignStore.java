package com.telcobright.seed.campaign.jdbc;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.spi.CampaignStore;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            ps.setString(i, json.apply(t.detail()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("task " + t.uniqueId() + " could not be inserted: " + e.getMessage(), e);
        }
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
        TaskCharge ch = t.charge() == null ? TaskCharge.FREE : t.charge();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(UPDATE_TASK)) {
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
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("task " + t.uniqueId() + " could not be updated: " + e.getMessage(), e);
        }
    }

    @Override
    public void bumpCounters(String tenantId, int campaignId, int sentDelta, int failedDelta, int pendingDelta) {
        if (counters == Counters.COUNTER_TABLE) {
            bumpTheCounterTable(campaignId, sentDelta, failedDelta, pendingDelta);
            return;
        }
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(BUMP)) {
            ps.setInt(1, sentDelta);
            ps.setInt(2, failedDelta);
            ps.setInt(3, pendingDelta);
            ps.setObject(4, stamp(Instant.now()));
            ps.setInt(5, campaignId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("counters of campaign " + campaignId + " could not be bumped: " + e.getMessage(), e);
        }
    }

    /** ONE upsert: the campaign's counter row is made by its first bump and added to by every later one; the campaign's row is not written. */
    private void bumpTheCounterTable(int campaignId, int sentDelta, int failedDelta, int pendingDelta) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(upsertOf(c))) {
            ps.setInt(1, campaignId);
            ps.setInt(2, sentDelta);
            ps.setInt(3, failedDelta);
            ps.setInt(4, pendingDelta);
            ps.setObject(5, stamp(Instant.now()));
            ps.setInt(6, pendingDelta);
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
