package com.telcobright.seed.switchledger.internal;

import com.telcobright.seed.sessionflow.api.RootResources;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/** A root's switch database by {@code DriverManager}: one connection per use, no pool (a host that wants a pool passes its own maker). */
public final class DriverDataSource implements DataSource {

    private final String url, username, password;
    private PrintWriter logWriter;
    private int loginTimeout;

    public DriverDataSource(RootResources.SwitchDb db, String password) {
        this.url = db.url();
        this.username = db.username();
        this.password = password;
    }

    @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, username, password); }
    @Override public Connection getConnection(String user, String pass) throws SQLException { return DriverManager.getConnection(url, user, pass); }
    @Override public PrintWriter getLogWriter() { return logWriter; }
    @Override public void setLogWriter(PrintWriter out) { this.logWriter = out; }
    @Override public void setLoginTimeout(int seconds) { this.loginTimeout = seconds; }
    @Override public int getLoginTimeout() { return loginTimeout; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException("no parent logger"); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
