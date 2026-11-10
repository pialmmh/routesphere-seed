package com.telcobright.seed.memledgerremote.dependencies;

/**
 * The words of a root whose ledger says {@code store: remote}: {@code address} (host:port of its mem-ledger server, on the host's 10.10
 * address), {@code deadline-ms} (the bound of every call and the port's {@code slowestAnswerMs()}; 2000 unless said), {@code token-var}
 * (the NAME of the environment variable holding the bearer — the value is read once at the start by the source and never held here),
 * and (ARCH-0119) the lock road's words, {@link LockRoad}: {@code lock-road} ({@code "on"} | {@code "off"}; off unless said),
 * {@code lock-lease-ms} and {@code lock-wait-ms}.
 */
public record RemoteLedgerSettings(String address, long deadlineMs, String tokenVar, LockRoad lockRoad) {

    public static final long DEFAULT_DEADLINE_MS = 2_000;

    public RemoteLedgerSettings {
        if (address == null || address.isBlank()) throw new IllegalArgumentException("ledger.address names the mem-ledger server as host:port");
        if (deadlineMs <= 0) throw new IllegalArgumentException("ledger.deadline-ms is a positive number of milliseconds, not " + deadlineMs);
        if (lockRoad == null) lockRoad = LockRoad.OFF;
    }

    /** The settings as they were before the lock road: it is off. */
    public RemoteLedgerSettings(String address, long deadlineMs, String tokenVar) { this(address, deadlineMs, tokenVar, LockRoad.OFF); }

    /**
     * ARCH-0119 road 2 · Does a unit of this root HOLD its rows on the server for its read-then-write, and for how long.
     *
     * @param on      the road is used; off = every unit is exactly the unit it was (rows as read, when the server checks them)
     * @param leaseMs how long one hold lives at most — the unit's read-then-write must fit in it (the owner's rule: one second at most)
     * @param waitMs  how long a unit waits for busy rows before it is answered "busy"; 0 = never wait
     */
    public record LockRoad(boolean on, int leaseMs, int waitMs) {

        public static final int LEASE_MS_MAX = 1_000;
        public static final int DEFAULT_LEASE_MS = 1_000;
        public static final int DEFAULT_WAIT_MS = 1_000;
        /** The rows one hold names at most (the server's own cap is 16 unless it says otherwise). */
        public static final int ROWS_ONE_HOLD_MAY_NAME = 16;

        public static final LockRoad OFF = new LockRoad(false, DEFAULT_LEASE_MS, DEFAULT_WAIT_MS);

        public LockRoad {
            if (leaseMs < 1 || leaseMs > LEASE_MS_MAX) throw new IllegalArgumentException("ledger.lock-lease-ms is 1 to " + LEASE_MS_MAX + " milliseconds, not " + leaseMs);
            if (waitMs < 0) throw new IllegalArgumentException("ledger.lock-wait-ms is 0 or more milliseconds, not " + waitMs);
        }

        /** The road on, with the default lease and wait. */
        public static LockRoad switchedOn() { return new LockRoad(true, DEFAULT_LEASE_MS, DEFAULT_WAIT_MS); }
    }
}
