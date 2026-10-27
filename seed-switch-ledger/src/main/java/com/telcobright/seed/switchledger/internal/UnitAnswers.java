package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.seed.sessionflow.api.CachedLedger.Committed;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * C1 · The first answer of every unit committed under a reference, in memory — ONE table per MemLedger, as the locks are
 * ({@link AccountLocks#of}): every door over the same ledger sees the same references. Bounded (the oldest forgotten first); a restart
 * forgets them all — where the ledger keeps {@code packageaccountcharge}, the unit's reference row answers then. Written only after the
 * record is in the WAL.
 */
final class UnitAnswers {

    private static final int KEPT = 100_000;
    private static final Map<MemLedger, UnitAnswers> PER_LEDGER = Collections.synchronizedMap(new WeakHashMap<>());

    private final Map<String, Committed> answered = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Committed> eldest) { return size() > KEPT; }
    };

    private UnitAnswers() {}

    static UnitAnswers of(MemLedger ledger) { return PER_LEDGER.computeIfAbsent(ledger, l -> new UnitAnswers()); }

    synchronized Committed first(String reference) { return answered.get(reference); }

    synchronized void remember(String reference, Committed first) { answered.put(reference, first); }
}
