package com.telcobright.seed.campaign.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The routesphere campaign schema, in both dialects: the ad-only tables ({@code campaign_target},
 * {@code campaign_creative}) beside routesphere's own, and the smallest routesphere subset a test or a fresh tenant
 * needs. On a routesphere MySQL tenant only the ad tables are created; on the wifi tenant's PostgreSQL the whole
 * subset goes into its own schema beside Odoo ({@link #postgresScript}).
 *
 * <p>{@code enumjobstatus} ids as found on the tenants (link3, 2026-09-24): 1 Complete, 2 Prepared, 3 Paused,
 * 4 Scheduled, 5 Failed, 6 Created, 7 Downloaded, 8 Canceled, 9 ReadyForPosting, 10 Running, 11 Sent, 12 Pending,
 * 13 Delivered, 15 Processing.
 */
public final class CampaignSchema {

    private CampaignSchema() {}

    /**
     * The widths of {@code campaign_task}'s text columns, in characters. The table below is made with them and the store
     * cuts a longer text to them ({@link JdbcCampaignStore}): one number, two uses. The task's id is never cut — a caller
     * mints ids that fit {@link #TASK_ID_WIDTH}.
     */
    public static final int TASK_ID_WIDTH = 50, TASK_NUMBER_WIDTH = 60, TASK_MESSAGE_WIDTH = 2000, TASK_CLIENT_REF_WIDTH = 255,
        TASK_TENANT_WIDTH = 255, TASK_UOM_WIDTH = 50, TASK_PREFIX_WIDTH = 100, TASK_CAUSE_WIDTH = 100;

    /** The ad tables. {@code target_value} (not {@code value}) so no dialect needs to quote it. */
    public static List<String> adTables(Dialect d) {
        return List.of(
            "CREATE TABLE IF NOT EXISTS campaign_target ("
                + id(d) + ", campaign_id INT NOT NULL, dimension VARCHAR(30) NOT NULL, target_value VARCHAR(100) NOT NULL,"
                + " CONSTRAINT uq_campaign_target UNIQUE (campaign_id, dimension, target_value))",
            "CREATE TABLE IF NOT EXISTS campaign_creative ("
                + id(d) + ", campaign_id INT NOT NULL, creative_id VARCHAR(64) NOT NULL, kind VARCHAR(10) NOT NULL,"
                + " media_ref VARCHAR(255) DEFAULT NULL, duration_sec INT DEFAULT 0, click_url VARCHAR(500) DEFAULT NULL,"
                + " caption VARCHAR(255) DEFAULT NULL, active SMALLINT NOT NULL DEFAULT 1,"
                + " CONSTRAINT uq_campaign_creative UNIQUE (campaign_id, creative_id))");
    }

    /** routesphere's tables, the columns this module reads and writes. */
    public static List<String> routesphereSubset(Dialect d) {
        return List.of(
            "CREATE TABLE IF NOT EXISTS enumjobstatus (id INT NOT NULL PRIMARY KEY, Type VARCHAR(45) NOT NULL)",
            "CREATE TABLE IF NOT EXISTS policy (" + id(d) + ", description VARCHAR(255), name VARCHAR(45) NOT NULL)",
            "CREATE TABLE IF NOT EXISTS time_band (" + id(d) + ", allow_or_restrict " + bit(d) + " NOT NULL, " + d.quote("day") + " VARCHAR(255),"
                + " end_time TIME, policy_id INT NOT NULL, specific_date_only " + ts(d) + ", start_time TIME)",
            "CREATE TABLE IF NOT EXISTS schedule_policy (" + id(d) + ", END_TIME " + ts(d) + ", NAME VARCHAR(255), START_TIME " + ts(d) + ")",
            "CREATE TABLE IF NOT EXISTS campaign ("
                + "CAMPAIGN_ID " + identity(d) + " PRIMARY KEY, CAMPAIGN_NAME VARCHAR(255), CAMPAIGN_TYPE VARCHAR(20), STATUS INT,"
                + " ID_PARTNER INT NOT NULL, EXPIRE_AT " + ts(d) + ", PRIORITY INT, TOTAL_TASK_COUNT INT, SENT_TASK_COUNT INT,"
                + " FAILED_TASK_COUNT INT, PENDING_TASK_COUNT INT, POLICY_ID INT, SCHEDULE_POLICY_ID INT, MESSAGE VARCHAR(255),"
                + " AUDIO_FILE_PATH VARCHAR(500), AUDIO_FILE_NAME VARCHAR(255), FIELD1 INT, FIELD2 INT, FIELD3 VARCHAR(255),"
                + " FIELD4 VARCHAR(255), FIELD5 VARCHAR(255), EXTERNAL_CAMPAIGN_ID VARCHAR(100) NOT NULL,"
                + " CREATED_STAMP " + ts(d) + ", LAST_UPDATED_STAMP " + ts(d) + ")",
            "CREATE TABLE IF NOT EXISTS campaign_task ("
                + "CAMPAIGN_TASK_ID " + identity(d) + " PRIMARY KEY, uniqueId VARCHAR(" + TASK_ID_WIDTH + ") NOT NULL, CAMPAIGN_ID INT NOT NULL,"
                + " ORIGINATING_CALLING_NUMBER VARCHAR(" + TASK_NUMBER_WIDTH + "), TERMINATING_CALLED_NUMBER VARCHAR(" + TASK_NUMBER_WIDTH + "), ID_PARTNER INT NOT NULL,"
                + " PHONE_NUMBER VARCHAR(" + TASK_NUMBER_WIDTH + ") NOT NULL, MESSAGE VARCHAR(" + TASK_MESSAGE_WIDTH + "), CLIENT_TRANS_ID VARCHAR(" + TASK_CLIENT_REF_WIDTH + "), CREATED_STAMP " + ts(d) + " NOT NULL,"
                + " LAST_UPDATED_STAMP " + ts(d) + ", RETRY_COUNT INT NOT NULL DEFAULT 0, STATE INT, STATUS INT, TASK_DETAIL_JSON " + longText(d) + ","
                + " idPackageAccount BIGINT, packageAmount DOUBLE PRECISION, uom VARCHAR(" + TASK_UOM_WIDTH + "), tenantName VARCHAR(" + TASK_TENANT_WIDTH + "), isPrepaid VARCHAR(10),"
                + " inPartnerCost DOUBLE PRECISION, MatchedPrefixCustomer VARCHAR(" + TASK_PREFIX_WIDTH + "), TASK_TYPE VARCHAR(20), ANSWERED SMALLINT DEFAULT 0,"
                + " START_TIME_MILLIS BIGINT, ANSWER_TIME_MILLIS BIGINT, END_TIME_MILLIS BIGINT, BILLSEC INT, HANGUP_CAUSE VARCHAR(" + TASK_CAUSE_WIDTH + "),"
                + " CONSTRAINT uq_campaign_task_uid UNIQUE (uniqueId))");
    }

    public static final String STATUS_ROWS =
        "INSERT INTO enumjobstatus (id, Type) VALUES (1,'Complete'),(2,'Prepared'),(3,'Paused'),(4,'Scheduled'),(5,'Failed'),"
            + "(6,'Created'),(7,'Downloaded'),(8,'Canceled'),(9,'ReadyForPosting'),(10,'Running'),(11,'Sent'),(12,'Pending'),(13,'Delivered'),(15,'Processing')";

    /** Only the ad tables — for a routesphere tenant that has the rest. Safe to run twice. */
    public static void createAdTables(Connection c, Dialect d) throws SQLException {
        run(c, adTables(d));
    }

    /** Everything, for a test database or a fresh tenant: the subset, the status rows, the ad tables. */
    public static void createAll(Connection c, Dialect d) throws SQLException {
        run(c, routesphereSubset(d));
        run(c, List.of(STATUS_ROWS));
        run(c, adTables(d));
    }

    /**
     * The whole schema as one psql script for the wifi tenant's PostgreSQL: a schema of its own beside Odoo's
     * {@code public}, the tables, the status rows, and the grant for the service role.
     */
    public static String postgresScript(String schema, String role) {
        return postgresScript(schema, role, List.of());
    }

    /** @param more statements a product adds to the same schema (ad-sphere: the rating and package tables) */
    public static String postgresScript(String schema, String role, List<String> more) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- routesphere's campaign schema for a PostgreSQL tenant — GENERATED from seed-campaign CampaignSchema; edit the Java, not this file.\n");
        sb.append("-- One schema beside Odoo's public in the SAME database (owner, 2026-09-24): one connection, one transaction, one backup.\n");
        sb.append("-- Apply once as the database owner:  psql -d <odoo db> -v ON_ERROR_STOP=1 -f routesphere-ad-schema.sql\n\n");
        sb.append("CREATE SCHEMA IF NOT EXISTS ").append(schema).append(";\n");
        sb.append("SET search_path TO ").append(schema).append(";\n\n");
        for (String s : routesphereSubset(Dialect.POSTGRES)) sb.append(s).append(";\n");
        sb.append("INSERT INTO enumjobstatus (id, Type) SELECT * FROM (VALUES (1,'Complete'),(2,'Prepared'),(3,'Paused'),(4,'Scheduled'),(5,'Failed'),")
          .append("(6,'Created'),(7,'Downloaded'),(8,'Canceled'),(9,'ReadyForPosting'),(10,'Running'),(11,'Sent'),(12,'Pending'),(13,'Delivered'),(15,'Processing')) AS v(id, type)")
          .append(" WHERE NOT EXISTS (SELECT 1 FROM enumjobstatus);\n");
        for (String s : adTables(Dialect.POSTGRES)) sb.append(s).append(";\n");
        for (String s : more) sb.append(s).append(";\n");
        sb.append("\nGRANT USAGE ON SCHEMA ").append(schema).append(" TO ").append(role).append(";\n");
        sb.append("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ").append(schema).append(" TO ").append(role).append(";\n");
        sb.append("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ").append(schema).append(" TO ").append(role).append(";\n");
        sb.append("ALTER DEFAULT PRIVILEGES IN SCHEMA ").append(schema).append(" GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ").append(role).append(";\n");
        return sb.toString();
    }

    private static String id(Dialect d) { return "id " + identity(d) + " PRIMARY KEY"; }

    private static String identity(Dialect d) {
        return d == Dialect.POSTGRES ? "BIGINT GENERATED BY DEFAULT AS IDENTITY" : "BIGINT NOT NULL AUTO_INCREMENT";
    }

    private static String ts(Dialect d) { return d == Dialect.POSTGRES ? "TIMESTAMP" : "DATETIME"; }

    private static String bit(Dialect d) { return d == Dialect.POSTGRES ? "BOOLEAN" : "BIT(1)"; }

    private static String longText(Dialect d) { return d == Dialect.POSTGRES ? "TEXT" : "LONGTEXT"; }

    private static void run(Connection c, List<String> statements) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String sql : statements) st.execute(sql);
        }
    }

    /** For a caller that wants every statement of a fresh install, in order. */
    public static List<String> all(Dialect d) {
        List<String> out = new ArrayList<>(routesphereSubset(d));
        out.add(STATUS_ROWS);
        out.addAll(adTables(d));
        return out;
    }
}
