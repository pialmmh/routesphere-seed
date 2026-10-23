package com.telcobright.seed.sessionflow.records;

import com.telcobright.seed.sessionflow.testkit.H2Locks;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * A DataSource that writes down what its connections are asked, in the database's log beside the lock functions' calls ({@link H2Locks}):
 * {@code begin}, {@code commit}, {@code rollback}, and each statement as {@code sql <its first words> @<thread>}.
 */
final class RecordingDataSource implements DataSource {

    private final DataSource db;
    private final String database;

    RecordingDataSource(DataSource db, String database) {
        this.db = db;
        this.database = database;
    }

    /** True = the store does not answer: every connection asked for is refused. */
    volatile boolean down;

    @Override
    public Connection getConnection() throws SQLException {
        if (down) {
            H2Locks.note(database, "refused");
            throw new SQLException("the store does not answer (the test's switch)", "08001");
        }
        Connection real = db.getConnection();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            noteThe(method.getName(), args);
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    private void noteThe(String method, Object[] args) {
        switch (method) {
            case "setAutoCommit" -> { if (Boolean.FALSE.equals(args[0])) H2Locks.note(database, "begin"); }
            case "commit" -> H2Locks.note(database, "commit");
            case "rollback" -> H2Locks.note(database, "rollback");
            case "prepareStatement" -> H2Locks.note(database, "sql " + firstWords((String) args[0]) + " @" + Thread.currentThread().getName());
            default -> { }
        }
    }

    private static String firstWords(String sql) {
        String[] words = sql.trim().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(4, words.length)));
    }

    @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
    @Override public PrintWriter getLogWriter() throws SQLException { return db.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { db.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { db.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return db.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { return db.unwrap(iface); }
    @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return db.isWrapperFor(iface); }
}
