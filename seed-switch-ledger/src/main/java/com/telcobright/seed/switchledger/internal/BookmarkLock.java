package com.telcobright.seed.switchledger.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * The EMBEDDED ledger's guard of its WAL bookmark row (the remote-fixes round, item (b)). mem-ledger keeps ONE bookmark row per ledger, in
 * the FIRST schema of its databases under a constant consumer id ({@code <first schema>.wal_consumer_offset}; f739824
 * {@code MemLedgerImpl.java:111, :455-458, :572-573}), and recall-db opens that same row in place (zero-move: same directory, same user).
 * Two ledgers on it move each other's WAL bookmark — so on PostgreSQL the embedded store takes the SAME session advisory lock recall-db
 * takes for the bookmark, on ONE dedicated connection held for the ledger's life and released after its shutdown; held elsewhere, the
 * open is refused in words. MySQL (and the H2 lab): no lock — recall-db is PostgreSQL only today — said once.
 *
 * <p>The lock's key and hash are COPIED from recall-db master 5534e69,
 * {@code src/main/java/com/telcobright/memledger/server/internal/InstanceLock.java}: {@code take} names it
 * {@code "recall-db-bookmark:" + firstSchema}, {@code tryLock} takes it with {@code SELECT pg_try_advisory_lock(hashtext(?))}, {@code close}
 * gives it back with {@code SELECT pg_advisory_unlock(hashtext(?))}. Never change one side without the other.
 */
public final class BookmarkLock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BookmarkLock.class);

    /** recall-db's own key prefix of the bookmark lock ({@code InstanceLock.take}). */
    public static final String KEY_PREFIX = "recall-db-bookmark:";

    private final String key;
    private Connection held;

    private BookmarkLock(String key, Connection held) {
        this.key = key;
        this.held = held;
    }

    /** The lock of {@code firstSchema}'s bookmark row on {@code db}: taken (PostgreSQL), none (any other database), or refused in words. */
    public static BookmarkLock take(DataSource db, String firstSchema) {
        String key = keyOf(firstSchema);
        Connection session = openTheSession(db, key);
        try {
            if (!isPostgres(session)) return noLockOn(session, firstSchema, key);
            session.setAutoCommit(true);                                         // held for days: never idle inside a transaction
            if (!tryLock(session, key)) throw heldElsewhere(firstSchema, key);
            log.info("the embedded ledger holds the lock '{}' of its bookmark row {}.wal_consumer_offset (recall-db's own) until its shutdown", key, firstSchema);
            return new BookmarkLock(key, session);
        } catch (SQLException e) {
            closeQuietly(session);
            throw new IllegalStateException("the lock '" + key + "' of the bookmark row " + firstSchema + ".wal_consumer_offset could not be taken: " + e.getMessage()
                + " — this ledger does not open", e);
        } catch (RuntimeException refused) {
            closeQuietly(session);                                               // a session's advisory locks go with its connection
            throw refused;
        }
    }

    /** recall-db's name of the lock of a bookmark row. */
    public static String keyOf(String firstSchema) { return KEY_PREFIX + firstSchema; }

    /** True while this ledger holds the lock (PostgreSQL, not yet released). */
    public synchronized boolean held() { return held != null; }

    /** Given back once (a second close is nothing): after the ledger's shutdown. */
    @Override
    public synchronized void close() {
        if (held == null) return;
        try (PreparedStatement ps = held.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
            ps.setString(1, key);
            ps.execute();
            log.info("the embedded ledger gave back the lock '{}' of its bookmark row", key);
        } catch (SQLException e) {
            log.warn("the lock '{}' of the bookmark row was not given back cleanly (its session's end releases it): {}", key, e.getMessage());
        } finally {
            closeQuietly(held);
            held = null;
        }
    }

    // ── the steps ───────────────────────────────────────────────────────────

    private static Connection openTheSession(DataSource db, String key) {
        try {
            return db.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("the lock '" + key + "' of the bookmark row could not be taken: no session — " + e.getMessage() + " — this ledger does not open", e);
        }
    }

    private static BookmarkLock noLockOn(Connection session, String firstSchema, String key) throws SQLException {
        log.info("the embedded ledger's bookmark row {}.wal_consumer_offset takes no lock: {} is not PostgreSQL (recall-db, the other ledger of a bookmark row,"
            + " is PostgreSQL only; its lock would be '{}')", firstSchema, session.getMetaData().getDatabaseProductName(), key);
        closeQuietly(session);
        return new BookmarkLock(key, null);
    }

    private static IllegalStateException heldElsewhere(String firstSchema, String key) {
        return new IllegalStateException("the bookmark row " + firstSchema + ".wal_consumer_offset is held by another ledger — recall-db? — this ledger does not open"
            + " (the PostgreSQL advisory lock '" + key + "' is taken; ONE ledger per bookmark row)");
    }

    private static boolean isPostgres(Connection c) throws SQLException {
        return c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
    }

    private static boolean tryLock(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void closeQuietly(Connection c) {
        if (c == null) return;
        try {
            c.close();
        } catch (SQLException ignored) {
            // the session is gone either way, and its advisory locks with it
        }
    }
}
