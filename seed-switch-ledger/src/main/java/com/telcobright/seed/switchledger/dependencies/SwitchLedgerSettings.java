package com.telcobright.seed.switchledger.dependencies;

/**
 * The knobs of the switch ledger (ARCH-0077-A item 3). They come from the product's profile file; nothing here is read from the environment.
 *
 * @param reaperMaxAgeMinutes a reserve row older than this is an orphan the reaper gives back — it MUST exceed the longest session, or a
 *                            live session's reserve is released under it (routesphere-core {@code PrepaidServiceWithCompensation.reapOrphanReserves}:
 *                            "that threshold must exceed the maximum possible call duration")
 * @param reaperBatchLimit    the most rows one pass of the reaper releases
 * @param lockTimeoutMs       how long a verb waits for an account's lock before it answers a {@code LedgerFault}
 */
public record SwitchLedgerSettings(int reaperMaxAgeMinutes, int reaperBatchLimit, long lockTimeoutMs) {

    public SwitchLedgerSettings {
        if (reaperMaxAgeMinutes <= 0) throw new IllegalArgumentException("reaperMaxAgeMinutes must be positive (and longer than the longest session)");
        if (reaperBatchLimit <= 0) throw new IllegalArgumentException("reaperBatchLimit must be positive");
        if (lockTimeoutMs <= 0) throw new IllegalArgumentException("lockTimeoutMs must be positive");
    }

    /** An hour before a row is an orphan, 500 rows per pass, five seconds for a lock. */
    public static SwitchLedgerSettings standard() { return new SwitchLedgerSettings(60, 500, 5000); }
}
