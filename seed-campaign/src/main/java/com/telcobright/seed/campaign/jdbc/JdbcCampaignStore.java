package com.telcobright.seed.campaign.jdbc;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.spi.CampaignStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        this.ds = ds;
        this.kind = kind;
        this.json = json;
        this.dialect = dialect;
        this.reader = new CampaignRowReader(dialect);
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
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT_TASK)) {
            int i = 1;
            ps.setString(i++, t.uniqueId());
            ps.setInt(i++, t.campaignId());
            ps.setInt(i++, t.partnerId());
            ps.setString(i++, t.subject());
            ps.setString(i++, t.creativeId());
            ps.setString(i++, t.kind().name());
            ps.setInt(i++, t.state().code());
            ps.setInt(i++, STATUS_PROCESSING);
            ps.setTimestamp(i++, Timestamp.from(t.createdAt()));
            ps.setTimestamp(i++, Timestamp.from(t.createdAt()));
            ps.setLong(i++, t.createdAt().toEpochMilli());
            ps.setString(i++, t.zone());
            ps.setString(i++, t.site());
            ps.setString(i++, t.clientRef());
            ps.setString(i++, t.tenantId());
            ps.setString(i, json.apply(t.detail()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("task " + t.uniqueId() + " could not be inserted: " + e.getMessage(), e);
        }
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
            ps.setString(i++, t.endCause());
            setLong(ps, i++, ch.packageAccountId());
            ps.setDouble(i++, ch.packageAmount() == null ? 0 : ch.packageAmount().doubleValue());
            ps.setString(i++, ch.uom());
            ps.setDouble(i++, ch.cost() == null ? 0 : ch.cost().doubleValue());
            ps.setString(i++, ch.free() ? "0" : "1");
            ps.setString(i++, ch.matchedPattern());
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
