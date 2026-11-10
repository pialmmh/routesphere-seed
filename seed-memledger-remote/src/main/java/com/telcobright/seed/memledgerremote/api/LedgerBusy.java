package com.telcobright.seed.memledgerremote.api;

/**
 * ARCH-0119 · The rows asked are busy: their queue is full (refused at once) or they were not free in time. Nothing is wrong with the
 * request and nothing changed: try again, or give the house answer. NEVER a money refusal.
 */
public final class LedgerBusy extends RuntimeException {

    private final String code;

    public LedgerBusy(String code, String words) {
        super(words);
        this.code = code;
    }

    /** {@code QUEUE_FULL}, {@code NOT_FREE_IN_TIME}, {@code TOO_MANY_ROWS} or {@code REFUSED}, as the server said it. */
    public String code() { return code; }
}
