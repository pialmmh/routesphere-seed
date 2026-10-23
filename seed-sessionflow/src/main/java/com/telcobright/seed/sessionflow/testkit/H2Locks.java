package com.telcobright.seed.sessionflow.testkit;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The batch lock's functions on H2 (P1-R §C): H2 has neither MySQL's {@code GET_LOCK} / {@code RELEASE_LOCK} nor PostgreSQL's
 * {@code pg_try_advisory_xact_lock}, so a test database gets them as Java functions ({@link RecordTables#create} defines them) — the writer's
 * real lock statements run on it. Each grants at once and is written down per database, in order, beside what a test notes there
 * ({@link #note}): the proof of the lock's place in the batch.
 */
public final class H2Locks {

    private static final Map<String, List<String>> CALLS = new ConcurrentHashMap<>();

    private H2Locks() {}

    public static int getLock(Connection c, String name, int waitSeconds) throws SQLException {
        note(c.getCatalog(), "lock " + name);
        return 1;
    }

    public static int releaseLock(Connection c, String name) throws SQLException {
        note(c.getCatalog(), "unlock " + name);
        return 1;
    }

    public static boolean tryAdvisoryXactLock(Connection c, long key) throws SQLException {
        note(c.getCatalog(), "xlock " + key);
        return true;
    }

    /** An event of a database, in its order. */
    public static void note(String database, String event) { CALLS.computeIfAbsent(database.toLowerCase(), d -> new CopyOnWriteArrayList<>()).add(event); }

    /** What a database was asked, in order. */
    public static List<String> calls(String database) { return List.copyOf(CALLS.getOrDefault(database.toLowerCase(), List.of())); }

    static List<String> aliases() {
        String self = H2Locks.class.getName();
        return List.of(
            "CREATE ALIAS IF NOT EXISTS GET_LOCK FOR '" + self + ".getLock'",
            "CREATE ALIAS IF NOT EXISTS RELEASE_LOCK FOR '" + self + ".releaseLock'",
            "CREATE ALIAS IF NOT EXISTS PG_TRY_ADVISORY_XACT_LOCK FOR '" + self + ".tryAdvisoryXactLock'");
    }
}
