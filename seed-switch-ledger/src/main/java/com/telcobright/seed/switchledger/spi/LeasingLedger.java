package com.telcobright.seed.switchledger.spi;

import com.telcobright.core.wal.WALEntryBatch;
import com.telcobright.memledger.api.response.EntityResponse;
import com.telcobright.seed.switchledger.spi.ExpectingLedger.AsRead;

import java.util.List;

/**
 * ARCH-0119 road 2 (the lock road) · A ledger that can HOLD rows for one unit's read-then-write: the unit names its rows, the ledger
 * holds them all or none and answers them as held, the unit computes, and its one batch is written under that hold. Between the hold and
 * the write no other writer — of this process or of another application on the same ledger — changes a held row, so two units on one
 * account take turns instead of one of them failing as stale.
 *
 * <p>The ledger's own words bound it (a hold lives one second at most; a row's queue is short): a row that is not free in time, or whose
 * queue is full, is {@link RowsBusy} at once — nothing held, nothing written, nothing wrong. A unit that outlives its hold loses it:
 * its write is {@link RowsBusy} too and changed nothing.
 *
 * <p>The engine asks {@link #holdsRows()} per unit, as it asks {@link ExpectingLedger#checksExpectations()}: a ledger in the process, or
 * a root whose lock road is off, answers false and the unit is exactly the unit it was.
 */
public interface LeasingLedger {

    /** Is the lock road ON for this ledger? False = a unit never asks for a hold. */
    boolean holdsRows();

    /** How many rows one hold may name; a unit that names more holds the first ones and reads the rest as any unit does. */
    int rowsOneHoldMayName();

    /**
     * How many units of THIS process may wait for one account at a time (the owner's rule, inside one application as on the server:
     * a short queue per account; no place in it = answered "busy" at once, never a longer queue).
     */
    int waitersOneRowMayHave();

    /**
     * Hold every row — all or none — and read them as held.
     *
     * @throws RowsBusy a row's queue is full (refused at once) or the rows were not free in time: nothing is held
     */
    Held hold(List<Row> rows);

    /**
     * The batch, under the hold. A row the batch changes is one the hold covers, a row in {@code asRead} (checked as
     * {@link ExpectingLedger#execCrud(WALEntryBatch, List)} checks it), or a new row. The hold ENDS with the answer, whatever it is.
     *
     * @throws RowsBusy the hold was lost before the write (it ran out, or the server started again): nothing written
     */
    EntityResponse execCrud(Held held, WALEntryBatch batch, List<AsRead> asRead);

    /** No write will come: the rows go back now. Never throws — a hold that cannot be given back ends by itself within its lease. */
    void release(Held held);

    /** One row to hold: its tier, its entity's registered name, its key. */
    record Row(String tier, String entity, Object id) {}

    /** The rows one unit holds. */
    interface Held {

        /** Does this hold cover the row? */
        boolean holds(String tier, String entity, Object id);

        /** The row as the ledger held it at the grant ({@code null} = it held none; the key is held all the same). Only for a row this holds. */
        <T> T row(String tier, String entity, Object id);
    }

    /** The rows are busy, or the hold was lost: nothing was written and nothing is wrong — try again, or answer "busy". Never a money refusal. */
    final class RowsBusy extends RuntimeException {
        private final String code;

        public RowsBusy(String code, String words) {
            super(words);
            this.code = code;
        }

        /** The ledger's own word: {@code QUEUE_FULL}, {@code NOT_FREE_IN_TIME}, {@code LOCK_LOST}, … */
        public String code() { return code; }
    }
}
