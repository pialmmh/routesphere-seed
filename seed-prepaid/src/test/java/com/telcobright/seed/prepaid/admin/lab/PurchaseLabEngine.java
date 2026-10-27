package com.telcobright.seed.prepaid.admin.lab;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

/**
 * L2c · One engine the purchase road is proven on: its three tiers (the operator's and two resellers'), each holding the ad lane's money
 * tables (prime-context's PostgreSQL lines verbatim, or their MySQL twins), made fresh by {@link #freshTiers()} and gone at
 * {@link #close()}. Every address is 127.0.0.1 (or in-process); a password is read by its variable's NAME, never written anywhere.
 */
public interface PurchaseLabEngine extends AutoCloseable {

    String name();

    /**
     * Why this engine's lab is not on this machine (the scenario is then SKIPPED, as seed-idgen's live test is without its binary), or
     * null when it is: a lab that is here but fails is a failure, never a skip.
     */
    default String absentBecause() { return null; }

    /** The tiers' database (MySQL) or schema (PostgreSQL, H2) names: the root first. */
    List<String> tiers();

    /** Every tier dropped if there and made again with its tables, and the package rows {@code 1..PACKAGES} a purchase may name. */
    void freshTiers() throws SQLException;

    /** What the ledger reads and writes by (PostgreSQL: a role with the purchase road's rights, the tables owned by another role). */
    DataSource ledgerDataSource();

    /** The lab's own hands on the tables (seeding, reading back). */
    DataSource labDataSource();

    /** The tiers (and the lab's principal, where it made one) are gone; the pools closed. */
    @Override
    void close() throws Exception;

    /** The packages every tier holds (a purchase names one; prime-context's packagepurchase.id_package is UNIQUE). */
    int PACKAGES = 400;

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
