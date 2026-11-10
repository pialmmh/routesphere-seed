package com.telcobright.seed.memledgerremote.api;

import com.telcobright.seed.memledgerremote.wire.LockedRow;

import java.util.List;

/**
 * ARCH-0119 road 2 · Rows the server holds for this caller between its read and its write: 1 s at most, then the server takes them
 * back. The rows are as the server's cache held them at the grant. Between the grant and the write: compute only — no other remote call.
 */
public record Lease(String lockId, String token, int leaseMs, List<LockedRow> rows) {

    /** The row as the server held it at the grant; null = it held none (the key is locked all the same). */
    public <T> T row(Class<T> type, String tier, String entity, Object id) {
        String key = RowJson.keyText(id);
        for (LockedRow row : rows) {
            if (row.getKey().getDbName().equals(tier) && row.getKey().getEntityName().equals(entity) && row.getKey().getEntityId().equals(key)) {
                return row.getFound() ? RowJson.rowOf(type, row.getEntityJson()) : null;
            }
        }
        throw new IllegalArgumentException("lock " + lockId + " does not hold " + tier + "." + entity + " id=" + key);
    }

    /** One row to lock. */
    public record Row(String tier, String entity, Object id) {}
}
