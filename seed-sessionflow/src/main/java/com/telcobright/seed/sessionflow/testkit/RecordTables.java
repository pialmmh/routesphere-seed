package com.telcobright.seed.sessionflow.testkit;

import com.telcobright.seed.sessionflow.dependencies.RecordDialect;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * billing-core's tables of the records road on an in-process H2 database, for the tests of every product (P1-R §C): in each tier's schema
 * {@code cdr} — billing-core's PostgreSQL columns (its {@code sql/postgres/billing-tables.sql}), or the legacy 104 on MySQL — {@code cdrerror}
 * like it, and {@code summary_affected}; the unique index billing-core's PostgreSQL DDL makes ({@code ux_cdr_call}), and on MySQL the by-hand
 * one ({@code cdr_idempotency.sql}) only when asked. H2 runs in the dialect's mode ({@link #url}); its types are billing-core's, a
 * {@code text} as {@code VARCHAR}; a partition is not made (H2 has none).
 */
public final class RecordTables {

    /** billing-core's 104 legacy columns (the PostgreSQL DDL's types; its NOT NULLs). */
    static final String LEGACY_COLUMNS =
        "SwitchId INTEGER NOT NULL, IdCall BIGINT NOT NULL, SequenceNumber BIGINT NOT NULL, FileName VARCHAR, "
        + "ServiceGroup INTEGER NOT NULL, IncomingRoute VARCHAR, OriginatingIP VARCHAR, OPC INTEGER, OriginatingCIC INTEGER, "
        + "OriginatingCalledNumber VARCHAR, TerminatingCalledNumber VARCHAR, OriginatingCallingNumber VARCHAR, "
        + "TerminatingCallingNumber VARCHAR, PrePaid INTEGER, DurationSec DECIMAL(20,8), EndTime TIMESTAMP, "
        + "ConnectTime TIMESTAMP, AnswerTime TIMESTAMP, ChargingStatus INTEGER, PDD REAL, CountryCode VARCHAR, "
        + "AreaCodeOrLata VARCHAR, ReleaseDirection INTEGER, ReleaseCauseSystem INTEGER, ReleaseCauseEgress INTEGER, "
        + "OutgoingRoute VARCHAR, TerminatingIP VARCHAR, DPC INTEGER, TerminatingCIC INTEGER, StartTime TIMESTAMP NOT NULL, "
        + "InPartnerId INTEGER, CustomerRate DECIMAL(20,8), OutPartnerId INTEGER, SupplierRate DECIMAL(20,8), "
        + "MatchedPrefixY VARCHAR, UsdRateY DECIMAL(20,8), MatchedPrefixCustomer VARCHAR, MatchedPrefixSupplier VARCHAR, "
        + "InPartnerCost DECIMAL(20,8), OutPartnerCost DECIMAL(20,8), CostAnsIn DECIMAL(20,8), CostIcxIn DECIMAL(20,8), "
        + "Tax1 DECIMAL(20,8), IgwRevenueIn DECIMAL(20,8), RevenueAnsOut DECIMAL(20,8), RevenueIgwOut DECIMAL(20,8), "
        + "RevenueIcxOut DECIMAL(20,8), Tax2 DECIMAL(20,8), XAmount DECIMAL(20,8), YAmount DECIMAL(20,8), AnsPrefixOrig VARCHAR, "
        + "AnsIdOrig INTEGER, AnsPrefixTerm VARCHAR, AnsIdTerm INTEGER, ValidFlag INTEGER, PartialFlag INTEGER, "
        + "ReleaseCauseIngress INTEGER, InRoamingOpId INTEGER, OutRoamingOpId INTEGER, CalledPartyNOA INTEGER, "
        + "CallingPartyNOA INTEGER, AdditionalSystemCodes VARCHAR, AdditionalPartyNumber VARCHAR, ResellerIds VARCHAR, "
        + "ZAmount DECIMAL(20,8), PreviousRoutes VARCHAR, E1Id INTEGER, MediaIp1 VARCHAR, MediaIp2 VARCHAR, MediaIp3 VARCHAR, "
        + "MediaIp4 VARCHAR, CallReleaseDuration REAL, E1IdOut INTEGER, InTrunkAdditionalInfo VARCHAR, "
        + "OutTrunkAdditionalInfo VARCHAR, InMgwId VARCHAR, OutMgwId VARCHAR, MediationComplete INTEGER, Codec VARCHAR, "
        + "ConnectedNumberType INTEGER, RedirectingNumber VARCHAR, CallForwardOrRoamingType INTEGER, OtherDate TIMESTAMP, "
        + "SummaryMetaTotal DECIMAL(20,8), TransactionMetaTotal DECIMAL(20,8), ChargeableMetaTotal DECIMAL(20,8), "
        + "ErrorCode VARCHAR, NERSuccess INTEGER, RoundedDuration DECIMAL(20,8), PartialDuration DECIMAL(20,8), "
        + "PartialAnswerTime TIMESTAMP, PartialEndTime TIMESTAMP, FinalRecord BIGINT, Duration1 DECIMAL(20,8), "
        + "Duration2 DECIMAL(20,8), Duration3 DECIMAL(20,8), Duration4 DECIMAL(20,8), PreviousPeriodCdr INTEGER, "
        + "UniqueBillId VARCHAR, AdditionalMetaData VARCHAR, Category INTEGER, SubCategory INTEGER, ChangedByJobId BIGINT, "
        + "SignalingStartTime TIMESTAMP";

    /** The ratified wire's six, after the 104 (PostgreSQL only). */
    static final String WIRE_COLUMNS =
        "ResellerHierarchy VARCHAR, ChannelCallUuid VARCHAR, HangupCause VARCHAR, InPartnerUom VARCHAR, IdPackageAccount BIGINT, "
        + "PackageAmount DECIMAL(20,8)";

    private RecordTables() {}

    /** An in-memory H2 database in the dialect's mode, kept while the JVM runs. */
    public static String url(String name, RecordDialect dialect) {
        String mode = dialect == RecordDialect.POSTGRESQL ? "PostgreSQL" : "MySQL";
        return "jdbc:h2:mem:" + name + ";MODE=" + mode + ";DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
    }

    /** Each tier's schema with its three tables; {@code byHandIndex} = MySQL's {@code ux_cdr_uniquebillid} too (PostgreSQL always has its own). */
    public static void create(DataSource db, RecordDialect dialect, boolean byHandIndex, String... tiers) {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            for (String tier : tiers) createTier(st, tier, dialect, byHandIndex);
        } catch (SQLException e) {
            throw new IllegalStateException("the records tables could not be made: " + e.getMessage(), e);
        }
    }

    private static void createTier(Statement st, String tier, RecordDialect dialect, boolean byHandIndex) throws SQLException {
        boolean postgres = dialect == RecordDialect.POSTGRESQL;
        String columns = postgres ? LEGACY_COLUMNS + ", " + WIRE_COLUMNS : LEGACY_COLUMNS;
        st.execute("CREATE SCHEMA IF NOT EXISTS " + tier);
        st.execute("CREATE TABLE " + tier + ".cdr (" + columns + ")");
        st.execute("CREATE TABLE " + tier + ".cdrerror (" + columns + ")");
        if (postgres) st.execute("CREATE UNIQUE INDEX ux_cdr_call ON " + tier + ".cdr (ChannelCallUuid, StartTime)");
        if (!postgres && byHandIndex) st.execute("CREATE UNIQUE INDEX ux_cdr_uniquebillid ON " + tier + ".cdr (UniqueBillId)");
        st.execute("CREATE INDEX ix_cdr_idcall ON " + tier + ".cdr (IdCall)");
        st.execute("CREATE TABLE " + tier + ".summary_affected (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
            + " entity_type VARCHAR(32) NOT NULL, op VARCHAR(8) DEFAULT 'add' NOT NULL, data VARCHAR NOT NULL)");
    }
}
