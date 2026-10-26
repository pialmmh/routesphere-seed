package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.memledger.api.ReloadResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * P1-G · A reseller tier created live brings rows OUTSIDE its own schema: its own account at its PARENT (the {@code packageaccount} row the
 * provisioning makes in the parent's schema with the tier; P2 gap 8 — ACCOUNT_NOT_FOUND at the parent before). After the tier's schema is
 * loaded, mem-ledger's {@code reloadNewEntities()} merges every row the cache lacks, in every schema of the root, add-only. A RESERVE row it
 * would bring back is a ghost — a settled reserve whose row the write-behind has not deleted yet (this ledger is the only writer of its
 * root's reserves: STANDALONE) — and is taken out again at once: left in, the reaper would give its money back a second time.
 *
 * <p>Its cost, once per tier created live, under the port's write gate (no money move of the root meanwhile): the pinned mem-ledger
 * (1.0.0-SNAPSHOT of 2026-10-07) reads both tables of every tier schema of the root WHOLE; from mem-ledger 0f04624 on, the accounts above
 * each schema's high-water mark and the reserve table whole.
 */
final class NewRowsOfATier {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NewRowsOfATier.class);

    private NewRowsOfATier() {}

    /** @return the rows merged, the ghost reserves not counted */
    static int merge(MemLedger memLedger) {
        Map<String, Set<Object>> reservesBefore = reservesOf(memLedger);
        ReloadResult read = memLedger.reloadNewRows();                       // L2b item 3 (S6): main's reload, its failures said, not the deprecated count
        if (!read.isComplete()) log.error("the switch ledger's reload of new rows was partial — the rest was merged: {}", read.failures());
        return read.added() - takeOutTheGhosts(memLedger, reservesBefore);
    }

    private static Map<String, Set<Object>> reservesOf(MemLedger memLedger) {
        Map<String, Set<Object>> byDb = new HashMap<>();
        for (String db : memLedger.getRegisteredDatabases()) byDb.put(db, new HashSet<>(memLedger.getAllEntities(db, Books.RESERVE).keySet()));
        return byDb;
    }

    /** L2b item 1 · Every ghost of every tier taken out in ONE unit (one WAL record), as every write of the switch ledger is. */
    private static int takeOutTheGhosts(MemLedger memLedger, Map<String, Set<Object>> reservesBefore) {
        UnitOfWork unit = new UnitOfWork(memLedger);
        for (String db : memLedger.getRegisteredDatabases()) {
            Set<Object> before = reservesBefore.getOrDefault(db, Set.of());
            for (Object key : Set.copyOf(memLedger.getAllEntities(db, Books.RESERVE).keySet())) {
                if (!before.contains(key)) unit.delete(db, Books.RESERVE, key);
            }
        }
        int ghosts = unit.size();
        unit.commit("take out " + ghosts + " ghost reserve row(s) a merge brought back");
        return ghosts;
    }
}
