package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.dependencies.RecordStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ONE transaction of one root's batch (P1-R §B), in billing-core's order ({@code MySqlCdrBatchRunner.RunInternal}): autocommit off → the batch
 * lock of every tier it writes → per session its CDR statements per tier, then its SDR statements → per tier ONE {@code summary_affected} row
 * of the records written now → commit → the locks released. Anything that fails rolls the whole batch back.
 */
public final class RecordBatch {

    /** billing-core's outbox row: {@code SummaryOutboxWriter.DefaultEntityType}, {@code OpAdd}. */
    static final String ENTITY = "cdr", ADD = "add";

    /** What a committed batch wrote: per tier, the records its summary row holds; and the records written before (not summed). */
    public record Written(Map<String, Integer> summedByTier, int writtenNow, int writtenBefore) {}

    /** The records of a batch, per tier: what is summed. */
    private static final class Summary {
        final Map<String, List<Object>> entriesByTier = new TreeMap<>();
        int writtenNow, writtenBefore;
    }

    private RecordBatch() {}

    /** The batch on one connection of the root's store, in one transaction. Throws when it did not commit (it rolled back). */
    public static Written write(RecordStore store, List<SessionRecords> sessions) throws SQLException {
        try (Connection connection = store.dataSource().getConnection()) {
            return inOneTransaction(connection, BatchLock.of(store.dialect()), sessions);
        }
    }

    private static Written inOneTransaction(Connection connection, BatchLock lock, List<SessionRecords> sessions) throws SQLException {
        List<String> held = new ArrayList<>();
        connection.setAutoCommit(false);
        try {
            lock.take(connection, tiersOf(sessions), held);
            Written written = writeTheBatch(connection, sessions);
            connection.commit();
            return written;
        } catch (SQLException | RuntimeException e) {
            rollBack(connection, e);
            throw e;
        } finally {
            lock.release(connection, held);
            restoreAutoCommit(connection);
        }
    }

    private static Written writeTheBatch(Connection connection, List<SessionRecords> sessions) throws SQLException {
        IdBook ids = new IdBook(connection);
        Summary summary = new Summary();
        for (SessionRecords session : sessions) writeOneSession(connection, session, ids, summary);
        Map<String, Integer> summed = writeTheSummaryInput(connection, summary, ids);
        return new Written(summed, summary.writtenNow, summary.writtenBefore);
    }

    /** Its records per tier (the once rule of {@code StatementRunner}), then its SDR. */
    private static void writeOneSession(Connection connection, SessionRecords session, IdBook ids, Summary summary) throws SQLException {
        for (SessionRecords.TierRecord tier : session.tiers()) {
            if (!StatementRunner.writes(connection, tier.statements(), ids)) { summary.writtenBefore++; continue; }
            summary.writtenNow++;
            if (tier.summaryEntry() != null) summary.entriesByTier.computeIfAbsent(CdrTableSql.tierOf(tier.record()), t -> new ArrayList<>()).add(tier.summaryEntry());
        }
        StatementRunner.writes(connection, session.sdr(), ids);
    }

    /** One row per tier that has records written now ({@code SummaryOutboxWriter.Write}: none for a tier with none). */
    private static Map<String, Integer> writeTheSummaryInput(Connection connection, Summary summary, IdBook ids) throws SQLException {
        Map<String, Integer> summed = new TreeMap<>();
        for (Map.Entry<String, List<Object>> tier : summary.entriesByTier.entrySet()) {
            try (PreparedStatement ps = connection.prepareStatement("insert into " + tier.getKey() + ".summary_affected (entity_type, op, data) values (?, ?, ?)")) {
                ps.setString(1, ENTITY);
                ps.setString(2, ADD);
                ps.setString(3, SummaryBlob.encode(tier.getValue(), ids));
                ps.executeUpdate();
            }
            summed.put(tier.getKey(), tier.getValue().size());
        }
        return summed;
    }

    private static Set<String> tiersOf(List<SessionRecords> sessions) {
        Set<String> tiers = new TreeSet<>();
        for (SessionRecords session : sessions) for (SessionRecords.TierRecord tier : session.tiers()) tiers.add(CdrTableSql.tierOf(tier.record()));
        return tiers;
    }

    private static void rollBack(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // best effort: the pool takes the connection back either way
        }
    }
}
