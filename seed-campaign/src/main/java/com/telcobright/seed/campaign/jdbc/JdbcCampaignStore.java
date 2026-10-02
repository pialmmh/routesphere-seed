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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * routesphere's schema on JDBC (MySQL; H2 in MySQL mode for tests). One store = one tenant database = one campaign
 * kind. Task rows are written in the SMS/voice columns with the meaning the design gives them ({@code PHONE_NUMBER}
 * = subject, {@code MESSAGE} = creative id, the voice millis = the view's lifecycle, the billing columns = the charge).
 * The detail map is serialised by the product-supplied {@code json} function (this module has no JSON library).
 */
public final class JdbcCampaignStore implements CampaignStore {

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

    /** {@code enumjobstatus} ids that a task's STATUS mirrors (the SMS runner writes the same ones). */
    static final int STATUS_COMPLETE = 1, STATUS_FAILED = 5, STATUS_SENT = 11, STATUS_PROCESSING = 15;

    private static final Logger log = LoggerFactory.getLogger(JdbcCampaignStore.class);

    private final AtomicLong cuts = new AtomicLong();
    private final DataSource ds;
    private final CampaignKind kind;
    private final Function<Map<String, Object>, String> json;
    private final Dialect dialect;
    private final CampaignRowReader reader;

    /** The dialect read from the pool's URL ({@code jdbc:postgresql:} → PostgreSQL, else MySQL). */
    public JdbcCampaignStore(DataSource ds, CampaignKind kind, Function<Map<String, Object>, String> json) {
        this(ds, kind, json, Dialect.of(ds));
    }

    public JdbcCampaignStore(DataSource ds, CampaignKind kind, Function<Map<String, Object>, String> json, Dialect dialect) {
        this(ds, kind, json, dialect, null);
    }

    /** @param creativesSql the product's own creatives query (see {@code CampaignRowReader}); null = the legacy {@code campaign_creative} rows */
    public JdbcCampaignStore(DataSource ds, CampaignKind kind, Function<Map<String, Object>, String> json, Dialect dialect, String creativesSql) {
        this.ds = ds;
        this.kind = kind;
        this.json = json;
        this.dialect = dialect;
        this.reader = new CampaignRowReader(dialect, creativesSql);
    }

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
            ps.setTimestamp(i++, Timestamp.from(t.createdAt()));
            ps.setTimestamp(i++, Timestamp.from(t.createdAt()));
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
            ps.setTimestamp(i++, Timestamp.from(Instant.now()));
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
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(BUMP)) {
            ps.setInt(1, sentDelta);
            ps.setInt(2, failedDelta);
            ps.setInt(3, pendingDelta);
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setInt(5, campaignId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("counters of campaign " + campaignId + " could not be bumped: " + e.getMessage(), e);
        }
    }

    @Override
    public void markComplete(String tenantId, int campaignId) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(COMPLETE)) {
            ps.setInt(1, STATUS_COMPLETE);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
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
