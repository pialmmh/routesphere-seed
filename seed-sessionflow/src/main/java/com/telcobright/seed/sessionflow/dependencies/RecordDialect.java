package com.telcobright.seed.sessionflow.dependencies;

/**
 * The SQL a records store speaks (P1-R): how the default {@code cdr} SQL and the batch lock are written — billing-core's two engines
 * ({@code MySqlEdge}, {@code PostgresEdge}).
 */
public enum RecordDialect {
    /** A tier is a DATABASE: the legacy 104 columns of {@code cdr}, billing-core's key {@code UniqueBillId}, the lock {@code GET_LOCK}. */
    MYSQL,
    /** A tier is a SCHEMA of the switch database: the 104 and the ratified wire's six, the key {@code ChannelCallUuid}, an advisory lock. */
    POSTGRESQL
}
