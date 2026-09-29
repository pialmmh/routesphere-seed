package com.telcobright.seed.callflow.internal;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.callflow.api.AdCallPayload;
import com.telcobright.seed.callflow.api.AdCause;
import com.telcobright.seed.callflow.spi.AdCdrPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The terminal write of an ad call (design §2.5, decision D10), in ONE JDBC transaction on the ROOT tenant database:
 * <ol>
 *   <li>one {@code ad_cdr} row per tier — routesphere's {@code CallDetailRecord} columns (the 38 of the CSV) + {@code tenant},
 *       {@code resellerHierarchy}, {@code serviceGroup 30}: {@code partnerId} = the tier's partner, {@code tenant} = the
 *       tier's db, {@code inPartnerCost} = the tier's charge, {@code idPackageAccount / uom / packageAmount / balanceBefore},
 *       {@code matchPrefixCustomer} = the rate prefix, {@code outPartnerId} = the network division, {@code callId} = the
 *       campaign id, {@code channelReadCodecName} = the media kind; a REJECT or a TIMEOUT gets a row too (no tier admitted →
 *       one row on the entry tenant with the advertiser when known);</li>
 *   <li>the {@code campaign_task} row of the view: updated by {@code uniqueId} (the claim), inserted when no claim exists;</li>
 *   <li>one {@code summary_affected(entity_type = 'ad_cdr', op = 'add', data = blob v2)} row;</li>
 *   <li>the CDR sequence ({@code cdr_state}, the {@code CdrSequenceService} idea) advanced;</li>
 *   <li>commit — then the Kafka ping ({@code cdr_summary_ping}), a {@link Runnable} port so tests need no broker.</li>
 * </ol>
 * The money is NOT in this transaction (it is orchestrix's, already debited at admission); every RECORD of the money is.
 * The table name {@code ad_cdr} keeps clear of the tenant database's mediation {@code cdr} table (exchange X-0001 §2.3).
 * H2 (MySQL mode) and MySQL.
 */
public final class LevelCdrWriter implements AdCdrPort {

    private static final Logger log = LoggerFactory.getLogger(LevelCdrWriter.class);
    public static final String CDR_TABLE = "ad_cdr";
    public static final String TASK_TABLE = "campaign_task";
    public static final String OUTBOX_TABLE = "summary_affected";
    public static final String STATE_TABLE = "cdr_state";
    /** seed-campaign's task states: 11 SENT (the view completed), 5 FAILED. */
    public static final int STATE_DONE = 11, STATE_FAILED = 5;

    private static final String INSERT_CDR = "INSERT INTO " + CDR_TABLE + " (sequenceNo, originatingCallingNumber, terminatingCallingNumber, originatingCalledNumber, terminatingCalledNumber,"
        + " startTime, answerTime, endTime, durationSec, channelCallUuid, hangupCause, callerIp, receiverIp, tenant, supplierPrefix, supplierCost, isPrepaid,"
        + " inPartnerCost, inPartnerUom, costIcxIn, costAnsIn, revenueAnsOut, revenueIgwOut, packageAmount, inPartnerId, outPartnerId, ansIdTerm, ansPrefixTerm,"
        + " ansIdOrig, ansPrefixOrig, matchPrefixCustomer, callRatePerMinBdt, idPackageAccount, resellerHierarchy, channelReadCodecName, callId, pdd, serviceGroup,"
        + " balanceBefore, balanceAfter, levelIndex, partnerName, answered, createdAt)"
        + " VALUES (?,?,?,?,?, ?,?,?,?,?,?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?,?,?,?, ?,?,?,?,?,?,?,?,?,?, ?,?,?,?,?,?)";

    private final DataSource rootDb;
    private final Runnable ping;
    private final ZoneId zone;
    private final AtomicLong sequence = new AtomicLong(-1);

    /**
     * @param rootDb the root tenant database (decision D10)
     * @param ping   what rings {@code cdr_summary_ping} after the commit (a Kafka producer in production; a counter in tests)
     * @param zone   the local zone of {@code startTime} (the summary bucket source)
     */
    public LevelCdrWriter(DataSource rootDb, Runnable ping, ZoneId zone) {
        this.rootDb = rootDb;
        this.ping = ping == null ? () -> { } : ping;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
    }

    /** The tables this writer needs, created {@code IF NOT EXISTS} (the outbox exactly as summary-service's pinned DDL). Safe to run twice. */
    public LevelCdrWriter ensureSchema() {
        try (Connection c = rootDb.getConnection(); Statement st = c.createStatement()) {
            boolean h2 = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("h2");
            for (String sql : schema(h2)) st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("the CDR writer could not create its tables: " + e.getMessage(), e);
        }
        return this;
    }

    /** The DDL, MySQL wording; H2 in MySQL mode takes it too but for the ENUM. */
    public static List<String> schema(boolean h2) {
        String op = h2 ? "VARCHAR(16) NOT NULL DEFAULT 'add'" : "ENUM('add','subtract') NOT NULL DEFAULT 'add'";
        return List.of(
            "CREATE TABLE IF NOT EXISTS " + CDR_TABLE + " (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, sequenceNo BIGINT NOT NULL,"
                + " originatingCallingNumber VARCHAR(60), terminatingCallingNumber VARCHAR(60), originatingCalledNumber VARCHAR(60), terminatingCalledNumber VARCHAR(60),"
                + " startTime DATETIME(3) NOT NULL, answerTime DATETIME(3), endTime DATETIME(3), durationSec INT NOT NULL DEFAULT 0, channelCallUuid VARCHAR(100) NOT NULL,"
                + " hangupCause VARCHAR(64), callerIp VARCHAR(45), receiverIp VARCHAR(255), tenant VARCHAR(100) NOT NULL, supplierPrefix VARCHAR(50), supplierCost DECIMAL(20,8) NOT NULL DEFAULT 0,"
                + " isPrepaid INT NOT NULL DEFAULT 0, inPartnerCost DECIMAL(20,8) NOT NULL DEFAULT 0, inPartnerUom VARCHAR(20), costIcxIn DECIMAL(20,8), costAnsIn DECIMAL(20,8),"
                + " revenueAnsOut DECIMAL(20,8), revenueIgwOut DECIMAL(20,8), packageAmount DECIMAL(20,8) NOT NULL DEFAULT 0, inPartnerId INT, outPartnerId INT,"
                + " ansIdTerm BIGINT, ansPrefixTerm VARCHAR(20), ansIdOrig BIGINT, ansPrefixOrig VARCHAR(20), matchPrefixCustomer VARCHAR(50), callRatePerMinBdt DECIMAL(20,8),"
                + " idPackageAccount BIGINT, resellerHierarchy VARCHAR(255), channelReadCodecName VARCHAR(20), callId VARCHAR(64), pdd DOUBLE, serviceGroup INT NOT NULL DEFAULT 30,"
                + " balanceBefore DECIMAL(20,8), balanceAfter DECIMAL(20,8), levelIndex INT NOT NULL DEFAULT 0, partnerName VARCHAR(200), answered TINYINT NOT NULL DEFAULT 0,"
                + " createdAt DATETIME(3) NOT NULL, INDEX ix_ad_cdr_tenant_time (tenant, startTime), INDEX ix_ad_cdr_uuid (channelCallUuid))",
            "CREATE TABLE IF NOT EXISTS " + TASK_TABLE + " (CAMPAIGN_TASK_ID BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, uniqueId VARCHAR(50) NOT NULL, CAMPAIGN_ID INT NOT NULL,"
                + " ORIGINATING_CALLING_NUMBER VARCHAR(60), TERMINATING_CALLED_NUMBER VARCHAR(60), ID_PARTNER INT NOT NULL, PHONE_NUMBER VARCHAR(60) NOT NULL, MESSAGE VARCHAR(2000),"
                + " CLIENT_TRANS_ID VARCHAR(255), CREATED_STAMP DATETIME NOT NULL, LAST_UPDATED_STAMP DATETIME, RETRY_COUNT INT NOT NULL DEFAULT 0, STATE INT, STATUS INT,"
                + " TASK_DETAIL_JSON LONGTEXT, idPackageAccount BIGINT, packageAmount DOUBLE, uom VARCHAR(50), tenantName VARCHAR(255), isPrepaid VARCHAR(10), inPartnerCost DOUBLE,"
                + " MatchedPrefixCustomer VARCHAR(100), TASK_TYPE VARCHAR(20), ANSWERED SMALLINT DEFAULT 0, START_TIME_MILLIS BIGINT, ANSWER_TIME_MILLIS BIGINT, END_TIME_MILLIS BIGINT,"
                + " BILLSEC INT, HANGUP_CAUSE VARCHAR(100), CONSTRAINT uq_campaign_task_uid UNIQUE (uniqueId))",
            "CREATE TABLE IF NOT EXISTS " + OUTBOX_TABLE + " (id BIGINT NOT NULL AUTO_INCREMENT, entity_type VARCHAR(32) NOT NULL, op " + op + ", data LONGTEXT NOT NULL,"
                + " PRIMARY KEY (id), INDEX ix_entity (entity_type, id))",
            "CREATE TABLE IF NOT EXISTS " + STATE_TABLE + " (id BIGINT PRIMARY KEY, cdr_state BIGINT NOT NULL DEFAULT 1000000)");
    }

    @Override
    public void writeAllLevels(AdCallPayload p, List<LevelAdmission> levels, String cause, boolean answered) {
        List<LevelAdmission> tiers = levels == null ? List.of() : levels;
        String outcome = AdCause.NORMAL_CLEARING.name().equals(cause) ? "done" : "failed";
        try (Connection c = rootDb.getConnection()) {
            c.setAutoCommit(false);
            try {
                long seq = nextSequence(c);
                if (tiers.isEmpty()) {
                    insertCdr(c, seq, p, null, cause, answered);
                } else {
                    for (LevelAdmission level : tiers) insertCdr(c, seq, p, level, cause, answered);
                }
                upsertTask(c, p, tiers, cause, answered);
                insertOutbox(c, AdCdrBlob.pack(AdCdrBlob.json(p, tiers, cause, answered, outcome, zone)));
                saveSequence(c, seq);
                c.commit();
            } catch (RuntimeException | SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the CDR of " + p.uniqueId() + " could not be written (nothing was): " + e.getMessage(), e);
        }
        try { ping.run(); } catch (RuntimeException e) { log.warn("cdr_summary_ping failed after the commit of {} (the outbox row waits for the next ping): {}", p.uniqueId(), e.toString()); }
    }

    private void insertCdr(Connection c, long seq, AdCallPayload p, LevelAdmission level, String cause, boolean answered) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(INSERT_CDR)) {
            int i = 1;
            ps.setLong(i++, seq);
            ps.setString(i++, p.originatingCallingNumber());
            ps.setString(i++, p.terminatingCallingNumber() != null ? p.terminatingCallingNumber() : p.mac());
            ps.setString(i++, p.originatingCalledNumber());
            ps.setString(i++, p.terminatingCalledNumber());
            ps.setTimestamp(i++, ts(p.startTimeMillis()));
            ps.setTimestamp(i++, p.answerTimeMillis() > 0 ? ts(p.answerTimeMillis()) : null);
            ps.setTimestamp(i++, p.endTimeMillis() > 0 ? ts(p.endTimeMillis()) : null);
            ps.setInt(i++, p.billsec());
            ps.setString(i++, p.uniqueId());
            ps.setString(i++, cause);
            ps.setString(i++, p.ip());
            ps.setString(i++, hostOf(p.mediaRef()));
            ps.setString(i++, level != null ? level.getDbName() : p.tenantName());
            ps.setString(i++, null);                                           // supplierPrefix
            ps.setBigDecimal(i++, BigDecimal.ZERO);                            // supplierCost: 0 this round (design §2.5)
            ps.setInt(i++, level != null && level.getPartner() != null && level.getPartner().getCustomerPrePaid() != null ? level.getPartner().getCustomerPrePaid() : 0);
            BigDecimal charge = level == null || level.getReservedAmount() == null ? BigDecimal.ZERO : level.getReservedAmount();
            boolean unitUom = level != null && level.getUom() != null && level.getUom().toUpperCase(Locale.ROOT).startsWith("AD_");
            ps.setBigDecimal(i++, unitUom ? BigDecimal.ZERO : charge);          // inPartnerCost: money
            ps.setString(i++, level == null ? null : level.getUom());
            ps.setBigDecimal(i++, null); ps.setBigDecimal(i++, null); ps.setBigDecimal(i++, null); ps.setBigDecimal(i++, null);
            ps.setBigDecimal(i++, unitUom ? charge : BigDecimal.ZERO);          // packageAmount: units
            setInt(ps, i++, level != null ? Integer.valueOf(level.getPartnerId()) : p.inPartnerId());
            setInt(ps, i++, p.outPartnerId());
            ps.setObject(i++, null); ps.setString(i++, null); ps.setObject(i++, null); ps.setString(i++, null);
            ps.setString(i++, level != null && level.getRatePrefix() != null ? level.getRatePrefix() : p.matchedDialplanPrefix());
            ps.setBigDecimal(i++, level == null ? null : level.getRate());
            ps.setObject(i++, level == null ? null : level.getPackageAccountId());
            ps.setString(i++, level == null ? null : hierarchyOf(level));
            ps.setString(i++, p.mediaKind());
            ps.setString(i++, p.campaignId() == null ? null : String.valueOf(p.campaignId()));
            ps.setObject(i++, p.answerTimeMillis() > 0 && p.startTimeMillis() > 0 ? (p.answerTimeMillis() - p.startTimeMillis()) / 1000.0 : null);
            ps.setInt(i++, AdCdrBlob.SERVICE_GROUP_AD);
            ps.setBigDecimal(i++, level == null ? null : level.getBalanceBefore());
            ps.setBigDecimal(i++, level == null ? null : level.getBalanceAfter());
            ps.setInt(i++, level == null ? 0 : level.getLevelIndex());
            ps.setString(i++, level == null ? null : level.getPartnerName());
            ps.setInt(i++, answered ? 1 : 0);
            ps.setTimestamp(i++, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();
        }
    }

    /** The claim of the view (today's {@code AdCampaignExecutor.take}) updated with the end; a view that never claimed gets its row here. */
    private void upsertTask(Connection c, AdCallPayload p, List<LevelAdmission> tiers, String cause, boolean answered) throws SQLException {
        LevelAdmission leaf = tiers.isEmpty() ? null : tiers.get(0);
        int state = AdCause.NORMAL_CLEARING.name().equals(cause) ? STATE_DONE : STATE_FAILED;
        boolean unitUom = leaf != null && leaf.getUom() != null && leaf.getUom().toUpperCase(Locale.ROOT).startsWith("AD_");
        BigDecimal charge = leaf == null || leaf.getReservedAmount() == null ? BigDecimal.ZERO : leaf.getReservedAmount();
        int updated;
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + TASK_TABLE + " SET STATE = ?, ANSWERED = ?, ANSWER_TIME_MILLIS = ?, END_TIME_MILLIS = ?, BILLSEC = ?, HANGUP_CAUSE = ?,"
            + " idPackageAccount = ?, packageAmount = ?, uom = ?, inPartnerCost = ?, MatchedPrefixCustomer = ?, LAST_UPDATED_STAMP = ? WHERE uniqueId = ?")) {
            int i = 1;
            ps.setInt(i++, state);
            ps.setInt(i++, answered ? 1 : 0);
            ps.setObject(i++, p.answerTimeMillis() > 0 ? p.answerTimeMillis() : null);
            ps.setObject(i++, p.endTimeMillis() > 0 ? p.endTimeMillis() : null);
            ps.setInt(i++, p.billsec());
            ps.setString(i++, cause);
            ps.setObject(i++, leaf == null ? null : leaf.getPackageAccountId());
            ps.setDouble(i++, unitUom ? charge.doubleValue() : 0.0);
            ps.setString(i++, leaf == null ? null : leaf.getUom());
            ps.setDouble(i++, unitUom ? 0.0 : charge.doubleValue());
            ps.setString(i++, leaf == null ? null : leaf.getRatePrefix());
            ps.setTimestamp(i++, new Timestamp(System.currentTimeMillis()));
            ps.setString(i++, p.uniqueId());
            updated = ps.executeUpdate();
        }
        if (updated > 0) return;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + TASK_TABLE + " (uniqueId, CAMPAIGN_ID, ORIGINATING_CALLING_NUMBER, TERMINATING_CALLED_NUMBER, ID_PARTNER, PHONE_NUMBER,"
            + " MESSAGE, CLIENT_TRANS_ID, CREATED_STAMP, LAST_UPDATED_STAMP, STATE, TASK_TYPE, ANSWERED, START_TIME_MILLIS, ANSWER_TIME_MILLIS, END_TIME_MILLIS, BILLSEC, HANGUP_CAUSE,"
            + " idPackageAccount, packageAmount, uom, tenantName, isPrepaid, inPartnerCost, MatchedPrefixCustomer)"
            + " VALUES (?,?,?,?,?,?, ?,?,?,?,?,?,?,?,?,?,?,?, ?,?,?,?,?,?,?)")) {
            int i = 1;
            ps.setString(i++, p.uniqueId());
            ps.setInt(i++, p.campaignId() == null ? 0 : p.campaignId());
            ps.setString(i++, p.originatingCallingNumber());
            ps.setString(i++, p.terminatingCalledNumber());
            ps.setInt(i++, p.inPartnerId() == null ? 0 : p.inPartnerId());
            ps.setString(i++, p.originatingCallingNumber() == null ? (p.mac() == null ? "-" : p.mac()) : p.originatingCallingNumber());
            ps.setString(i++, p.contentId());
            ps.setString(i++, p.wifiSessionId());
            ps.setTimestamp(i++, ts(p.startTimeMillis() > 0 ? p.startTimeMillis() : System.currentTimeMillis()));
            ps.setTimestamp(i++, new Timestamp(System.currentTimeMillis()));
            ps.setInt(i++, state);
            ps.setString(i++, AdCallPayload.TASK_TYPE_AD);
            ps.setInt(i++, answered ? 1 : 0);
            ps.setObject(i++, p.startTimeMillis() > 0 ? p.startTimeMillis() : null);
            ps.setObject(i++, p.answerTimeMillis() > 0 ? p.answerTimeMillis() : null);
            ps.setObject(i++, p.endTimeMillis() > 0 ? p.endTimeMillis() : null);
            ps.setInt(i++, p.billsec());
            ps.setString(i++, cause);
            ps.setObject(i++, leaf == null ? null : leaf.getPackageAccountId());
            ps.setDouble(i++, unitUom ? charge.doubleValue() : 0.0);
            ps.setString(i++, leaf == null ? null : leaf.getUom());
            ps.setString(i++, p.tenantName());
            ps.setString(i++, leaf == null ? "0" : "1");
            ps.setDouble(i++, unitUom ? 0.0 : charge.doubleValue());
            ps.setString(i++, leaf == null ? null : leaf.getRatePrefix());
            ps.executeUpdate();
        }
    }

    private static void insertOutbox(Connection c, String data) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + OUTBOX_TABLE + " (entity_type, op, data) VALUES (?, 'add', ?)")) {
            ps.setString(1, AdCdrBlob.ENTITY_TYPE);
            ps.setString(2, data);
            ps.executeUpdate();
        }
    }

    /** {@code CdrSequenceService}'s idea: one row of {@code cdr_state}, read once at the first write, advanced in memory, saved in the transaction. */
    private long nextSequence(Connection c) throws SQLException {
        if (sequence.get() < 0) {
            synchronized (sequence) {
                if (sequence.get() < 0) {
                    long fromDb = 1_000_000L;
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT cdr_state FROM " + STATE_TABLE + " WHERE id = 1")) {
                        if (rs.next()) fromDb = rs.getLong(1);
                        else try (Statement ins = c.createStatement()) { ins.execute("INSERT INTO " + STATE_TABLE + " (id, cdr_state) VALUES (1, " + fromDb + ")"); }
                    }
                    sequence.set(fromDb);
                }
            }
        }
        return sequence.incrementAndGet();
    }

    private static void saveSequence(Connection c, long seq) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + STATE_TABLE + " SET cdr_state = ? WHERE id = 1 AND cdr_state < ?")) {
            ps.setLong(1, seq);
            ps.setLong(2, seq);
            ps.executeUpdate();
        }
    }

    /** {@code root > … > this tier}, as {@code CdrGenerator.buildResellerHierarchy} spells it. */
    public static String hierarchyOf(LevelAdmission level) {
        Tenant t = level == null ? null : level.getTenant();
        if (t == null) return null;
        List<String> up = new ArrayList<>();
        for (Tenant x : t.getAncestorChain()) up.add(x.getDbName());
        StringBuilder sb = new StringBuilder();
        for (int i = up.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) sb.append(" > ");
            sb.append(up.get(i));
        }
        return sb.toString();
    }

    private static Timestamp ts(long ms) { return new Timestamp(ms); }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setObject(i, null); else ps.setInt(i, v);
    }

    private static String hostOf(String ref) {
        if (ref == null || !ref.contains("://")) return null;
        try { return java.net.URI.create(ref).getHost(); } catch (RuntimeException e) { return null; }
    }

    static Instant instant(long ms) { return Instant.ofEpochMilli(ms); }
}
