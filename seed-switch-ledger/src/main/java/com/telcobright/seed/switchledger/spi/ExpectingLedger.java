package com.telcobright.seed.switchledger.spi;

import com.telcobright.core.wal.WALEntryBatch;
import com.telcobright.memledger.api.response.EntityResponse;

import java.util.List;

/**
 * ARCH-0118 S2 · A MemLedger that checks, under the batch's row locks, that every row a unit READ is still what it read (a recall-db
 * whose Health says {@code expect}): one that differs refuses the WHOLE batch as STALE — nothing written. Over such a ledger a unit
 * sends the rows it read with its batch, so two applications that read the same balance can no longer both write over it (the lost
 * update). Over any other ledger (the embedded MemLedger, an older server) a unit writes as before — one writer per ledger.
 */
public interface ExpectingLedger {

    /** Does this ledger check the rows a batch read? */
    boolean checksExpectations();

    /** The batch, written only if every row in {@code asRead} is still as read; else an error answer in the ledger's words, nothing changed. */
    EntityResponse execCrud(WALEntryBatch batch, List<AsRead> asRead);

    /** One row as the unit READ it from the ledger: its tier, its entity's registered name, its key, the row ({@code null} = there was none). */
    record AsRead(String tier, String entity, Object id, Object row) {}
}
