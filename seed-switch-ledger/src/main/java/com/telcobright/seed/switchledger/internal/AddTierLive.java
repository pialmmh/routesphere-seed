package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;

/**
 * Loads ONE new tier schema into a running MemLedger — {@code MemLedger.addDatabase(schema)} (mem-ledger fba1346, ARCH-0077-B2 item 3; on
 * the seed's mem-ledger since L2b, main 6523614): the tier's registered entities loaded as registered, nothing else opened or reloaded, a
 * tier already registered a no-op ({@code false}); a load that fails registers nothing and throws. L2b item 1: a plain call (the
 * reflection that reached it on the 2026-10-07 pin is gone).
 *
 * <p>Not {@code reloadDatabases()}: it works only on a MemLedger built with {@code autoDiscoverDatabases(prefix)}, whose discovery is
 * MySQL's {@code SHOW DATABASES LIKE '<prefix>%'} — a PostgreSQL switch database keeps its tiers as SCHEMAS (the bed's), where it fails,
 * and it loads every new database under the prefix, not the one tier the event names.
 */
public final class AddTierLive {

    private final MemLedger memLedger;

    private AddTierLive(MemLedger memLedger) { this.memLedger = memLedger; }

    /** The loader of this MemLedger. */
    public static AddTierLive of(MemLedger memLedger) { return new AddTierLive(memLedger); }

    /** Load the tier schema; true = loaded now, false = it was registered already. A load that fails is said in words. */
    public boolean load(String tierDb) {
        try {
            return memLedger.addDatabase(tierDb);
        } catch (RuntimeException e) {
            throw new IllegalStateException("the MemLedger did not load the tier schema '" + tierDb + "': " + e.getMessage(), e);
        }
    }
}
