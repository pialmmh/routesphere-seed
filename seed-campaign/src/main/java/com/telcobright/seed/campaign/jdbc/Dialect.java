package com.telcobright.seed.campaign.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * The two databases the routesphere schema lives on: MySQL (the routesphere tenants) and PostgreSQL (the wifi
 * tenant, whose tables sit in a schema beside Odoo's {@code public} — owner, 2026-09-25). The SQL is the same but
 * for the identifier quote and the DDL types; unquoted identifiers fold the same way on both once the DDL is
 * written unquoted (MySQL keeps the case but ignores it, PostgreSQL lowers it).
 */
public enum Dialect {
    MYSQL('`'), POSTGRES('"');

    private final char quote;

    Dialect(char quote) { this.quote = quote; }

    /** A column that is a keyword somewhere ({@code day} on H2), quoted the database's way. */
    public String quote(String identifier) { return quote + identifier + quote; }

    public static Dialect ofUrl(String jdbcUrl) {
        String u = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:postgresql:") || u.contains("mode=postgresql")) return POSTGRES;
        return MYSQL;
    }

    /** From the pool's own URL (H2 in a MySQL or PostgreSQL mode is read from its URL too). */
    public static Dialect of(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            return ofUrl(c.getMetaData().getURL());
        } catch (SQLException e) {
            throw new IllegalStateException("the campaign database cannot be reached to tell its dialect: " + e.getMessage(), e);
        }
    }
}
