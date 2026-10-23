package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.RecordId;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * The ids of ONE try of one batch ({@link RecordId}): each seed is asked once, on the batch's connection and under its lock; each key gets one
 * number. A try that rolls back throws its book away — the next try asks the seeds again.
 */
public final class IdBook {

    private final Connection connection;
    private final Map<String, Long> lastOfSeed = new HashMap<>();
    private final Map<RecordId, Long> numbers = new HashMap<>();

    public IdBook(Connection connection) { this.connection = connection; }

    /** The id's number: the same for its key all through the try; a new key takes the seed's last + 1. */
    public long numberOf(RecordId id) throws SQLException {
        Long known = numbers.get(id);
        if (known != null) return known;
        long next = lastOf(id.seed()) + 1;
        lastOfSeed.put(id.seed(), next);
        numbers.put(id, next);
        return next;
    }

    /** The number this try gave the id; 0 when none (its statement did not run). */
    public long given(RecordId id) { return numbers.getOrDefault(id, 0L); }

    private long lastOf(String seed) throws SQLException {
        Long last = lastOfSeed.get(seed);
        if (last != null) return last;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(seed)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}
