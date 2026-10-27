package com.telcobright.seed.prepaid.admin.lab;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * L2c · The local MySQL on 127.0.0.1:3306 (an LXC container: always the IP, never localhost); the tiers are databases of their own,
 * {@code l2c_lab_btcl}, {@code l2c_lab_res_2}, {@code l2c_lab_res_3}, dropped at the end. The user from {@code LAB_MYSQL_USER} (root), the
 * password by NAME from {@code LAB_MYSQL_PASSWORD}; {@code allowMultiQueries=true} for the consumer's batch of UPDATEs.
 */
public final class MysqlPurchaseLab implements PurchaseLabEngine {

    static final String HOST = "127.0.0.1";
    static final String PORT = PurchaseLabEngine.env("LAB_MYSQL_PORT", "3306");
    static final String USER = PurchaseLabEngine.env("LAB_MYSQL_USER", "root");
    static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&allowMultiQueries=true";
    private HikariDataSource pool;

    @Override public String name() { return "MySQL"; }
    @Override public String absentBecause() {
        String password = System.getenv("LAB_MYSQL_PASSWORD");
        return password == null || password.isBlank() ? "no lab MySQL here: LAB_MYSQL_PASSWORD is not in the environment" : null;
    }
    @Override public List<String> tiers() { return List.of("l2c_lab_btcl", "l2c_lab_res_2", "l2c_lab_res_3"); }
    @Override public DataSource labDataSource() { return pool(); }
    @Override public DataSource ledgerDataSource() { return pool(); }

    @Override
    public void freshTiers() throws SQLException {
        System.out.println("MysqlPurchaseLab: the lab MySQL at " + HOST + ":" + PORT + " as " + USER + " — databases " + tiers());
        try (Connection admin = connect(); Statement s = admin.createStatement()) {
            for (String tier : tiers()) {
                s.execute("DROP DATABASE IF EXISTS " + tier);
                s.execute("CREATE DATABASE " + tier);
                for (String ddl : MysqlTwins.of(tier)) s.execute(ddl);
            }
        } catch (SQLException e) {
            throw new SQLException("Lab MySQL unreachable at " + HOST + ":" + PORT + " as " + USER + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public void close() throws SQLException {
        if (pool != null) pool.close();
        try (Connection admin = connect(); Statement s = admin.createStatement()) {
            for (String tier : tiers()) s.execute("DROP DATABASE IF EXISTS " + tier);
        }
    }

    private synchronized DataSource pool() {
        if (pool == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:mysql://" + HOST + ":" + PORT + "/" + tiers().get(0) + OPTIONS);
            config.setUsername(USER);
            config.setPassword(password());
            config.setMaximumPoolSize(8);
            config.setPoolName("l2c-lab-mysql");
            pool = new HikariDataSource(config);
        }
        return pool;
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT + "/" + OPTIONS, USER, password());
    }

    private static String password() {
        String password = System.getenv("LAB_MYSQL_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("Set LAB_MYSQL_PASSWORD in the environment (by name, from a file you never commit)");
        return password;
    }
}
