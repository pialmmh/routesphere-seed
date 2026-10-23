package com.telcobright.seed.sessionflow.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One prepared statement of a session's records (P1-R §A): the application's SQL, run by the base inside the batch's ONE transaction. A table
 * is qualified by its tier's schema — {@code <tier>.cdr}, as billing-core keeps every tier's rows in the tier's own schema — and every value is
 * a parameter, never text in the SQL. A {@link RecordId} parameter is a number the writer hands out under the batch lock.
 *
 * <p><b>Once per record.</b> A {@link #probe} is a query: when it answers a row, the record is written already — its other statements are
 * not run and it is not summed (billing-core's drop of what is already written). Otherwise the record's first statement that is not a probe
 * decides: it changed a row = the record is written now (and summed); it changed none (an {@code INSERT IGNORE}, an
 * {@code ON CONFLICT DO NOTHING}) = written before, and the rest is not run.
 *
 * @param sql    the statement, a {@code ?} for every value
 * @param params the values in order; null is SQL NULL
 * @param probe  true = a query that answers a row when the record is written already
 */
public record RecordStatement(String sql, List<Object> params, boolean probe) {

    public RecordStatement {
        Objects.requireNonNull(sql, "sql");
        params = params == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(params));
    }

    /** A statement that changes rows. */
    public RecordStatement(String sql, List<Object> params) { this(sql, params, false); }

    /** A statement that changes rows, its values in order. */
    public static RecordStatement of(String sql, Object... params) { return new RecordStatement(sql, Arrays.asList(params), false); }

    /** A query that answers a row when the record is written already. */
    public static RecordStatement probe(String sql, Object... params) { return new RecordStatement(sql, Arrays.asList(params), true); }
}
