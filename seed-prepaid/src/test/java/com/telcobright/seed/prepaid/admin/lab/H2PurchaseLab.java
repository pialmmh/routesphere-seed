package com.telcobright.seed.prepaid.admin.lab;

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** L2c · H2 in MySQL mode, in memory (the flags of the switch ledger's LedgerLab): the tiers are schemas holding the MySQL twins. */
public final class H2PurchaseLab implements PurchaseLabEngine {

    private static final AtomicInteger LABS = new AtomicInteger();
    private final JdbcDataSource h2 = new JdbcDataSource();
    private final String url;

    public H2PurchaseLab() {
        url = "jdbc:h2:mem:l2c-purchase-" + LABS.incrementAndGet() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        h2.setURL(url);
        h2.setUser("sa");
        h2.setPassword("");
    }

    @Override public String name() { return "H2"; }
    @Override public List<String> tiers() { return List.of("btcl", "res_2", "res_3"); }
    @Override public DataSource ledgerDataSource() { return h2; }
    @Override public DataSource labDataSource() { return h2; }

    @Override
    public void freshTiers() throws SQLException {
        System.out.println("H2PurchaseLab: no network listener — H2 in memory (" + url + ")");
        try (Connection c = h2.getConnection(); Statement s = c.createStatement()) {
            for (String tier : tiers()) {
                s.execute("DROP SCHEMA IF EXISTS " + tier + " CASCADE");
                s.execute("CREATE SCHEMA " + tier);
                for (String ddl : MysqlTwins.of(tier)) s.execute(ddl);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        try (Connection c = h2.getConnection(); Statement s = c.createStatement()) {
            s.execute("SHUTDOWN");
        }
    }
}
