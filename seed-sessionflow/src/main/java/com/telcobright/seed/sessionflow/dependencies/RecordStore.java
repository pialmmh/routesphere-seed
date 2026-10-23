package com.telcobright.seed.sessionflow.dependencies;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * One root's records store (P1-R §B): the database its tiers live in — one schema (PostgreSQL) or one database (MySQL) per tier, all reached
 * through this ONE pooled {@code DataSource} the application owns — and the SQL it speaks.
 *
 * @param root       the root's database name, as its CDR road names it ({@code btcl} of {@code cdr_btcl})
 * @param dataSource a POOLED DataSource; the root's writer borrows one connection per batch
 * @param dialect    MySQL or PostgreSQL
 * @param switchId   the {@code SwitchId} of the rows (billing-core's {@code billing.mediation.switch-id}: the source NE's idSwitch; 0 = unset)
 */
public record RecordStore(String root, DataSource dataSource, RecordDialect dialect, int switchId) {

    public RecordStore {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(dialect, "dialect");
    }
}
