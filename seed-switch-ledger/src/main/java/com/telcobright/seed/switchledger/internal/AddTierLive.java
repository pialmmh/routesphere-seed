package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Loads ONE new tier schema into a running MemLedger — {@code MemLedger.addDatabase(schema)} (mem-ledger fba1346, 2026-10-11, ARCH-0077-B2
 * item 3): the tier's registered entities loaded as registered, nothing else opened or reloaded, a tier already registered a no-op. The
 * seed's pin (mem-ledger 1.0.0-SNAPSHOT built 2026-10-07) predates it, so it is found on the MemLedger's class at run time; a MemLedger
 * without it is refused in words (the tier loads at the next start; until then its money is refused). When the pin moves to a build that
 * has it, this becomes a plain call.
 *
 * <p>Not {@code reloadDatabases()}: in the pinned mem-ledger it works only on a MemLedger built with {@code autoDiscoverDatabases(prefix)},
 * whose discovery is MySQL's {@code SHOW DATABASES LIKE '<prefix>%'} — a PostgreSQL switch database keeps its tiers as SCHEMAS (the bed's),
 * where it fails, and it loads every new database under the prefix, not the one tier the event names.
 */
public final class AddTierLive {

    private final Object memLedger;
    private final Method addDatabase;

    private AddTierLive(Object memLedger, Method addDatabase) {
        this.memLedger = memLedger;
        this.addDatabase = addDatabase;
    }

    /** The loader of this MemLedger (its class asked once). */
    public static AddTierLive of(Object memLedger) { return new AddTierLive(memLedger, addDatabaseOf(memLedger.getClass())); }

    /** Load the tier schema; true = loaded now, false = it was registered already. Refused in words when this MemLedger cannot. */
    public boolean load(String tierDb) {
        if (addDatabase == null) {
            throw new IllegalStateException("this MemLedger (" + memLedger.getClass().getName() + ") cannot load the tier schema '" + tierDb
                + "' live: it has no addDatabase(schema) (mem-ledger fba1346, 2026-10-11; the seed's pin mem-ledger 1.0.0-SNAPSHOT of 2026-10-07"
                + " predates it) — the tier's accounts load at the next start; until then its money is refused");
        }
        try {
            return Boolean.TRUE.equals(addDatabase.invoke(memLedger, tierDb));
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("the MemLedger did not load the tier schema '" + tierDb + "': " + e.getCause(), e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("the MemLedger's addDatabase cannot be called: " + e, e);
        }
    }

    /** The verb on MemLedger's own interface (a build that has it), else on the object's class (a test's stand-in); null = none. */
    private static Method addDatabaseOf(Class<?> type) {
        for (Class<?> where : new Class<?>[] {MemLedger.class, type}) {
            try {
                return where.getMethod("addDatabase", String.class);
            } catch (NoSuchMethodException e) {
                // not on this one
            }
        }
        return null;
    }
}
