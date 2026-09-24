package com.telcobright.seed.campaign.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The two ad-only tables beside routesphere's {@code campaign} / {@code campaign_task} (MySQL), and the smallest
 * routesphere schema a test needs (H2 in MySQL mode). The routesphere tables themselves are the tenant's; nothing here
 * creates or alters them on a real database.
 *
 * <p>{@code enumjobstatus} ids as found on the tenants (link3, 2026-09-25): 1 Complete, 2 Prepared, 3 Paused,
 * 4 Scheduled, 5 Failed, 6 Created, 7 Downloaded, 8 Canceled, 9 ReadyForPosting, 10 Running, 11 Sent, 12 Pending,
 * 13 Delivered, 15 Processing.
 */
public final class CampaignSchema {

    private CampaignSchema() {}

    /** The ad tables, MySQL. Safe to run twice. */
    public static final List<String> AD_TABLES_MYSQL = List.of(
        """
        CREATE TABLE IF NOT EXISTS campaign_target (
          id BIGINT NOT NULL AUTO_INCREMENT,
          campaign_id INT NOT NULL,
          dimension VARCHAR(30) NOT NULL,
          `value` VARCHAR(100) NOT NULL,
          PRIMARY KEY (id),
          UNIQUE KEY uq_campaign_target (campaign_id, dimension, `value`)
        )""",
        """
        CREATE TABLE IF NOT EXISTS campaign_creative (
          id BIGINT NOT NULL AUTO_INCREMENT,
          campaign_id INT NOT NULL,
          creative_id VARCHAR(64) NOT NULL,
          kind VARCHAR(10) NOT NULL,
          media_ref VARCHAR(255) DEFAULT NULL,
          duration_sec INT DEFAULT 0,
          click_url VARCHAR(500) DEFAULT NULL,
          caption VARCHAR(255) DEFAULT NULL,
          active TINYINT(1) NOT NULL DEFAULT 1,
          PRIMARY KEY (id),
          UNIQUE KEY uq_campaign_creative (campaign_id, creative_id)
        )""");

    /** routesphere's tables, the columns this module reads and writes — for H2 (MySQL mode) and a fresh tenant DB. */
    public static final List<String> ROUTESPHERE_SUBSET = List.of(
        """
        CREATE TABLE IF NOT EXISTS enumjobstatus (id INT NOT NULL, Type VARCHAR(45) NOT NULL, PRIMARY KEY (id))""",
        """
        CREATE TABLE IF NOT EXISTS policy (id INT NOT NULL AUTO_INCREMENT, description VARCHAR(255), name VARCHAR(45) NOT NULL, PRIMARY KEY (id))""",
        """
        CREATE TABLE IF NOT EXISTS time_band (id BIGINT NOT NULL AUTO_INCREMENT, allow_or_restrict BIT(1) NOT NULL, `day` VARCHAR(255),
          end_time TIME, policy_id INT NOT NULL, specific_date_only DATETIME, start_time TIME, PRIMARY KEY (id))""",
        """
        CREATE TABLE IF NOT EXISTS schedule_policy (ID INT NOT NULL AUTO_INCREMENT, END_TIME DATETIME, NAME VARCHAR(255), START_TIME DATETIME, PRIMARY KEY (ID))""",
        """
        CREATE TABLE IF NOT EXISTS campaign (
          CAMPAIGN_ID INT NOT NULL AUTO_INCREMENT, CAMPAIGN_NAME VARCHAR(255), CAMPAIGN_TYPE VARCHAR(20), STATUS INT,
          ID_PARTNER INT NOT NULL, EXPIRE_AT DATETIME, PRIORITY INT, TOTAL_TASK_COUNT INT, SENT_TASK_COUNT INT,
          FAILED_TASK_COUNT INT, PENDING_TASK_COUNT INT, POLICY_ID INT, SCHEDULE_POLICY_ID INT, MESSAGE VARCHAR(255),
          AUDIO_FILE_PATH VARCHAR(500), AUDIO_FILE_NAME VARCHAR(255), FIELD1 INT, FIELD2 INT, FIELD3 VARCHAR(255),
          FIELD4 VARCHAR(255), FIELD5 VARCHAR(255), EXTERNAL_CAMPAIGN_ID VARCHAR(100) NOT NULL,
          CREATED_STAMP DATETIME, LAST_UPDATED_STAMP DATETIME, PRIMARY KEY (CAMPAIGN_ID))""",
        """
        CREATE TABLE IF NOT EXISTS campaign_task (
          CAMPAIGN_TASK_ID BIGINT NOT NULL AUTO_INCREMENT, uniqueId VARCHAR(50) NOT NULL, CAMPAIGN_ID INT NOT NULL,
          ORIGINATING_CALLING_NUMBER VARCHAR(60), TERMINATING_CALLED_NUMBER VARCHAR(60), ID_PARTNER INT NOT NULL,
          PHONE_NUMBER VARCHAR(60) NOT NULL, MESSAGE VARCHAR(2000), CLIENT_TRANS_ID VARCHAR(255), CREATED_STAMP DATETIME NOT NULL,
          LAST_UPDATED_STAMP DATETIME, RETRY_COUNT INT NOT NULL DEFAULT 0, STATE INT, STATUS INT, TASK_DETAIL_JSON LONGTEXT,
          idPackageAccount BIGINT, packageAmount DOUBLE, uom VARCHAR(50), tenantName VARCHAR(255), isPrepaid VARCHAR(10),
          inPartnerCost DOUBLE, MatchedPrefixCustomer VARCHAR(100), TASK_TYPE VARCHAR(20), ANSWERED TINYINT(1) DEFAULT 0,
          START_TIME_MILLIS BIGINT, ANSWER_TIME_MILLIS BIGINT, END_TIME_MILLIS BIGINT, BILLSEC INT, HANGUP_CAUSE VARCHAR(100),
          PRIMARY KEY (CAMPAIGN_TASK_ID), UNIQUE KEY uq_campaign_task_uid (uniqueId))""");

    public static final List<String> STATUS_ROWS = List.of(
        "INSERT INTO enumjobstatus (id, Type) VALUES (1,'Complete'),(2,'Prepared'),(3,'Paused'),(4,'Scheduled'),(5,'Failed'),"
            + "(6,'Created'),(7,'Downloaded'),(8,'Canceled'),(9,'ReadyForPosting'),(10,'Running'),(11,'Sent'),(12,'Pending'),(13,'Delivered'),(15,'Processing')");

    public static void createAdTables(Connection c) throws SQLException {
        run(c, AD_TABLES_MYSQL);
    }

    /** Everything, for a test database or a fresh tenant: the subset, the status rows, the ad tables. */
    public static void createAll(Connection c) throws SQLException {
        run(c, ROUTESPHERE_SUBSET);
        run(c, STATUS_ROWS);
        run(c, AD_TABLES_MYSQL);
    }

    private static void run(Connection c, List<String> statements) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String sql : statements) st.execute(sql);
        }
    }
}
