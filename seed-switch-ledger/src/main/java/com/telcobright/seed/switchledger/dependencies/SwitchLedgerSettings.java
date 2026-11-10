package com.telcobright.seed.switchledger.dependencies;

/**
 * The knobs of the switch ledger (ARCH-0077-A item 3). They come from the product's profile file; nothing here is read from the environment.
 *
 * @param reaperMaxAgeMinutes a reserve row older than this is an orphan the reaper gives back — it MUST exceed the longest session, or a
 *                            live session's reserve is released under it (routesphere-core {@code PrepaidServiceWithCompensation.reapOrphanReserves}:
 *                            "that threshold must exceed the maximum possible call duration")
 * @param reaperBatchLimit    the most rows one pass of the reaper releases
 * @param lockTimeoutMs       how long a verb waits for an account's lock before it answers a {@code LedgerFault}
 * @param reservePrefix       ARCH-0118 S3 · two applications on one ledger: the reaper gives back only the reserves whose key (the
 *                            reference the application held them under) starts with this, its own word ({@value #RESERVE_PREFIX_WORD}
 *                            in the root's ledger block); null = every reserve, as before
 */
public record SwitchLedgerSettings(int reaperMaxAgeMinutes, int reaperBatchLimit, long lockTimeoutMs, String reservePrefix) {

    /** The root's ledger word an application names its own reserves' prefix with: {@code ledger.reserve-prefix}. */
    public static final String RESERVE_PREFIX_WORD = "reserve-prefix";

    public SwitchLedgerSettings {
        if (reaperMaxAgeMinutes <= 0) throw new IllegalArgumentException("reaperMaxAgeMinutes must be positive (and longer than the longest session)");
        if (reaperBatchLimit <= 0) throw new IllegalArgumentException("reaperBatchLimit must be positive");
        if (lockTimeoutMs <= 0) throw new IllegalArgumentException("lockTimeoutMs must be positive");
        if (reservePrefix != null && reservePrefix.isBlank()) reservePrefix = null;
    }

    /** The knobs as before ARCH-0118: the reaper gives back every reserve. */
    public SwitchLedgerSettings(int reaperMaxAgeMinutes, int reaperBatchLimit, long lockTimeoutMs) {
        this(reaperMaxAgeMinutes, reaperBatchLimit, lockTimeoutMs, null);
    }

    /** The same knobs, the reaper giving back only the reserves whose key starts with {@code prefix} (null or blank = every reserve). */
    public SwitchLedgerSettings withReservePrefix(String prefix) { return new SwitchLedgerSettings(reaperMaxAgeMinutes, reaperBatchLimit, lockTimeoutMs, prefix); }

    /** Is the reserve held under {@code key} this application's to give back? */
    public boolean ownsReserve(String key) { return reservePrefix == null || (key != null && key.startsWith(reservePrefix)); }

    /** An hour before a row is an orphan, 500 rows per pass, five seconds for a lock; every reserve the reaper's. */
    public static SwitchLedgerSettings standard() { return new SwitchLedgerSettings(60, 500, 5000); }
}
