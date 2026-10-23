package com.telcobright.seed.sessionflow.spi;

/**
 * The summary ping (P1-R §B) — billing-core's {@code cdr_summary_ping}: after a batch committed, "this tier has a new outbox row", so
 * summary-service drains it at once instead of at its next poll. It carries no data (the {@code summary_affected} row is the hand-off); a
 * ping never sent costs latency only. The base calls it after the commit, on the ping's own thread — never the writer's.
 */
@FunctionalInterface
public interface SummaryPing {

    /** No ping: summary-service finds each outbox row by its poll. */
    SummaryPing NONE = (tenant, entity, rows) -> { };

    /** One ping: the tier's schema that has a new outbox row, its entity ({@code cdr}), the records the row holds. May block; must not throw. */
    void ping(String tenant, String entity, int rows);
}
