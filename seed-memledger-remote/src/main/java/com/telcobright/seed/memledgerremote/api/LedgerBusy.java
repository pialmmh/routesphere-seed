package com.telcobright.seed.memledgerremote.api;

/**
 * ARCH-0119 · The rows asked are busy: their queue is full (refused at once), they were not free in time, or one of them is in doubt
 * ({@link WireBatch#IN_DOUBT}: its last write is not settled yet, so it is not read for a write). Nothing is wrong with the request
 * and nothing changed: try again, or give the house answer. NEVER a money refusal.
 */
public final class LedgerBusy extends RuntimeException {

    private final String code;

    public LedgerBusy(String code, String words) {
        super(words);
        this.code = code;
    }

    /** {@code QUEUE_FULL}, {@code NOT_FREE_IN_TIME}, {@code IN_DOUBT}, {@code TOO_MANY_ROWS} or {@code REFUSED}, as the server said it. */
    public String code() { return code; }
}
