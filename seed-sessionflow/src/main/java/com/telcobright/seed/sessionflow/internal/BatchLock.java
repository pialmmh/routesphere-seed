package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.dependencies.RecordDialect;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * billing-core's per-tier batch lock (P1-R §B): {@code billing_batch_<tier>} ({@code MySqlCdrBatchRunner.RunInternal}), so a billing-core
 * batch of the same tier and ours run one at a time. It is held ACROSS the commit — what keeps each tier's {@code summary_affected} ids
 * visible in commit order, the summary cursor's ({@code id > offset}) one need. A batch takes the lock of every tier it writes, in name
 * order (two of our writers never wait on each other in a ring; billing-core holds one at a time). A lock not taken within 30 s fails
 * the batch, as billing-core's does.
 *
 * <pre>
 *   MySQL       GET_LOCK(name, 30) per tier; RELEASE_LOCK after the commit or the rollback (a session lock, as billing-core's)
 *   PostgreSQL  pg_try_advisory_xact_lock(key) per tier, asked until 30 s; the commit or the rollback releases it. The key is billing-core's:
 *               the first 8 bytes of SHA-256(name) (PostgresEdge.LockKeyOf) — its session lock and our transaction lock are one lock space
 * </pre>
 */
public abstract class BatchLock {

    public static final int WAIT_SECONDS = 30;
    private static final long ASK_AGAIN_MS = 25;

    /** billing-core's lock of one tier. */
    public static String nameOf(String tier) { return "billing_batch_" + tier; }

    public static BatchLock of(RecordDialect dialect) { return dialect == RecordDialect.POSTGRESQL ? new Advisory() : new Named(); }

    /** Take the lock of every tier, in name order, each into {@code held} as it is taken; throws when one is not taken in time. */
    public final void take(Connection connection, Collection<String> tiers, List<String> held) throws SQLException {
        for (String tier : new TreeSet<>(tiers)) {
            takeOne(connection, nameOf(tier));
            held.add(nameOf(tier));
        }
    }

    /** After the commit or the rollback. Best effort: the end of the session releases a lock too. */
    public abstract void release(Connection connection, List<String> names);

    abstract void takeOne(Connection connection, String name) throws SQLException;

    /** billing-core's {@code PostgresEdge.LockKeyOf}. */
    public static long keyOf(String name) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Named extends BatchLock {
        @Override
        void takeOne(Connection connection, String name) throws SQLException {
            try (PreparedStatement ps = connection.prepareStatement("select get_lock(?, ?)")) {
                ps.setString(1, name);
                ps.setInt(2, WAIT_SECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 1) return;
                }
            }
            throw new SQLException("the batch lock " + name + " was not taken within " + WAIT_SECONDS + " s (another batch still running?)");
        }

        @Override
        public void release(Connection connection, List<String> names) {
            for (String name : names) {
                try (PreparedStatement ps = connection.prepareStatement("select release_lock(?)")) {
                    ps.setString(1, name);
                    ps.executeQuery().close();
                } catch (SQLException ignored) {
                    // the end of the session releases it too
                }
            }
        }
    }

    private static final class Advisory extends BatchLock {
        @Override
        void takeOne(Connection connection, String name) throws SQLException {
            long until = System.currentTimeMillis() + WAIT_SECONDS * 1000L;
            try (PreparedStatement ps = connection.prepareStatement("select pg_try_advisory_xact_lock(?)")) {
                ps.setLong(1, keyOf(name));
                while (true) {
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getBoolean(1)) return;
                    }
                    if (System.currentTimeMillis() >= until) break;
                    pause();
                }
            }
            throw new SQLException("the batch lock " + name + " was not taken within " + WAIT_SECONDS + " s (another batch still running?)");
        }

        @Override
        public void release(Connection connection, List<String> names) { }

        private static void pause() throws SQLException {
            try {
                Thread.sleep(ASK_AGAIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted while waiting for the batch lock", e);
            }
        }
    }
}
