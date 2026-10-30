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
 * {@code l2clabbtcl}, {@code l2clabres2}, {@code l2clabres3}, dropped at the end. The user from {@code LAB_MYSQL_USER} (root), the
 * password by NAME from {@code LAB_MYSQL_PASSWORD}; {@code allowMultiQueries=true} for the consumer's batch of UPDATEs.
 *
 * <p>ARCH-0100: the root's database word is the root's TENANT ID on the id service (the design: the root schema = the tenant id,
 * {@code ^[a-z][a-z0-9]{1,15}$}), so the lab's root is {@code l2clabbtcl} — the old {@code l2c_lab_btcl} broke the rule and a purchase
 * refused it in words before any mint.
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
    @Override public List<String> tiers() { return List.of("l2clabbtcl", "l2clabres2", "l2clabres3"); }
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
