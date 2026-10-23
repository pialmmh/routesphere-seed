package com.telcobright.seed.sessionflow.samples.scene;

import com.telcobright.seed.sessionflow.samples.bed.Crash;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * One node's records store (P1-R C2): the node dies the moment its FIRST commit is through — the batch is in the database, and nothing the
 * node does after it (the journal's done) reaches the world.
 */
public final class CrashAfterTheCommit implements DataSource {

    private final DataSource db;
    private final Crash crash;

    public CrashAfterTheCommit(DataSource db, Crash crash) {
        this.db = db;
        this.crash = crash;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection real = db.getConnection();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            try {
                Object answer = method.invoke(real, args);
                if (method.getName().equals("commit")) crash.now();
                return answer;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
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
