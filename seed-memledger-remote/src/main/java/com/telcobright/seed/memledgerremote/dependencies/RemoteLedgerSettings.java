package com.telcobright.seed.memledgerremote.dependencies;

/**
 * The words of a root whose ledger says {@code store: remote}: {@code address} (host:port of its mem-ledger server, on the host's 10.10
 * address), {@code deadline-ms} (the bound of every call and the port's {@code slowestAnswerMs()}; 2000 unless said), {@code token-var}
 * (the NAME of the environment variable holding the bearer — the value is read once at the start by the source and never held here),
 * and (ARCH-0119) the lock road's words, {@link LockRoad}: {@code lock-road} ({@code "on"} | {@code "off"}; off unless said),
 * {@code lock-lease-ms}, {@code lock-wait-ms} and {@code lock-queue-max}; and the one-call road's word, {@code one-call-moves}
 * ({@code "on"} | {@code "off"}; off unless said): a hold and a settle are then ONE call each.
 */
public record RemoteLedgerSettings(String address, long deadlineMs, String tokenVar, LockRoad lockRoad, boolean oneCallMoves) {

    public static final long DEFAULT_DEADLINE_MS = 2_000;

    public RemoteLedgerSettings {
        if (address == null || address.isBlank()) throw new IllegalArgumentException("ledger.address names the mem-ledger server as host:port");
        if (deadlineMs <= 0) throw new IllegalArgumentException("ledger.deadline-ms is a positive number of milliseconds, not " + deadlineMs);
        if (lockRoad == null) lockRoad = LockRoad.OFF;
    }

    /** The settings as they were before the lock road: it is off. */
    public RemoteLedgerSettings(String address, long deadlineMs, String tokenVar) { this(address, deadlineMs, tokenVar, LockRoad.OFF, false); }

    /** The settings with the lock road's words and the one-call road off. */
    public RemoteLedgerSettings(String address, long deadlineMs, String tokenVar, LockRoad lockRoad) { this(address, deadlineMs, tokenVar, lockRoad, false); }

    /** The same settings with the one-call road (ARCH-0119 road 1: {@code one-call-moves: "on"}) on or off. */
    public RemoteLedgerSettings withOneCallMoves(boolean on) { return new RemoteLedgerSettings(address, deadlineMs, tokenVar, lockRoad, on); }

    /**
     * ARCH-0119 road 2 · Does a unit of this root HOLD its rows on the server for its read-then-write, and for how long.
     *
     * @param on      the road is used; off = every unit is exactly the unit it was (rows as read, when the server checks them)
     * @param leaseMs how long one hold lives at most — the unit's read-then-write must fit in it (the owner's rule: one second at most)
     * @param waitMs  how long a unit waits for busy rows before it is answered "busy"; 0 = never wait
     * @param queueMax how many units of this application may wait for one account at a time; one more is answered "busy" at once
     */
    public record LockRoad(boolean on, int leaseMs, int waitMs, int queueMax) {

        public static final int LEASE_MS_MAX = 1_000;
        public static final int DEFAULT_LEASE_MS = 1_000;
        public static final int DEFAULT_WAIT_MS = 1_000;
        /** The rows one hold names at most (the server's own cap is 16 unless it says otherwise). */
        public static final int ROWS_ONE_HOLD_MAY_NAME = 16;
        /** The queue per account unless said: the server's own queue per row. */
        public static final int DEFAULT_QUEUE_MAX = 32;

        public static final LockRoad OFF = new LockRoad(false, DEFAULT_LEASE_MS, DEFAULT_WAIT_MS, DEFAULT_QUEUE_MAX);

        public LockRoad {
            if (leaseMs < 1 || leaseMs > LEASE_MS_MAX) throw new IllegalArgumentException("ledger.lock-lease-ms is 1 to " + LEASE_MS_MAX + " milliseconds, not " + leaseMs);
            if (waitMs < 0) throw new IllegalArgumentException("ledger.lock-wait-ms is 0 or more milliseconds, not " + waitMs);
            if (queueMax < 0) throw new IllegalArgumentException("ledger.lock-queue-max is 0 or more waiting units, not " + queueMax);
        }

        /** The road's words with the default queue. */
        public LockRoad(boolean on, int leaseMs, int waitMs) { this(on, leaseMs, waitMs, DEFAULT_QUEUE_MAX); }

        /** The road on, with the default lease and wait. */
        public static LockRoad switchedOn() { return new LockRoad(true, DEFAULT_LEASE_MS, DEFAULT_WAIT_MS, DEFAULT_QUEUE_MAX); }
    }
}
