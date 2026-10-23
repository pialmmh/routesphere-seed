package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.RecordId;
import com.telcobright.seed.sessionflow.api.RecordStatement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * One record's statements on its batch's connection (P1-R §A), by the once rule of {@link RecordStatement}: a probe that answers a row, or a
 * first change that changes nothing, is the record written before — nothing more of it runs. A {@link RecordId} value is bound as its number.
 */
public final class StatementRunner {

    private StatementRunner() {}

    /** True = the record is written now (it is summed); false = it was written before, or it had nothing to write. */
    public static boolean writes(Connection connection, List<RecordStatement> statements, IdBook ids) throws SQLException {
        boolean wrote = false;
        for (RecordStatement statement : statements) {
            if (statement.probe()) {
                if (!wrote && answersARow(connection, statement, ids)) return false;
                continue;
            }
            int changed = changedRows(connection, statement, ids);
            if (!wrote && changed == 0) return false;
            wrote = true;
        }
        return wrote;
    }

    private static boolean answersARow(Connection connection, RecordStatement probe, IdBook ids) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(probe.sql())) {
            bind(ps, probe.params(), ids);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int changedRows(Connection connection, RecordStatement change, IdBook ids) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(change.sql())) {
            bind(ps, change.params(), ids);
            return ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, List<Object> params, IdBook ids) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof RecordId id) ps.setLong(i + 1, ids.numberOf(id));
            else ps.setObject(i + 1, value);
        }
    }
}
