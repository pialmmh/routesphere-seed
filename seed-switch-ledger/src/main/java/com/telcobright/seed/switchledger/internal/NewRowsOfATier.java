package com.telcobright.seed.switchledger.internal;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.memledger.api.ReloadResult;
import com.telcobright.seed.switchledger.spi.GhostFreeReloads;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * P1-G · A reseller tier created live brings rows OUTSIDE its own schema: its own account at its PARENT (the {@code packageaccount} row the
 * provisioning makes in the parent's schema with the tier; P2 gap 8 — ACCOUNT_NOT_FOUND at the parent before). After the tier's schema is
 * loaded, mem-ledger's {@code reloadNewEntities()} merges every row the cache lacks, in every schema of the root, add-only. A RESERVE row it
 * would bring back is a ghost — a settled reserve whose row the write-behind has not deleted yet — and is taken out again at once: left
 * in, the reaper would give its money back a second time.
 *
 * <p>ARCH-0118 S1 · WHO takes it out. A ledger whose own reload takes out its ghosts ({@link GhostFreeReloads}: a recall-db whose Health
 * says {@code ghosts}, under a gate no write passes) is left to do it: this merge then takes none out, so a reserve ANOTHER application
 * inserts between this merge's two looks keeps its row. Any other ledger (the embedded MemLedger, an older server) keeps the client-side
 * rule below — right only while this ledger is the only writer of its root's reserves (STANDALONE).
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
        if (takesOutItsOwnGhosts(memLedger)) return reloaded(memLedger).added();
        Map<String, Set<Object>> reservesBefore = reservesOf(memLedger);
        ReloadResult read = reloaded(memLedger);
        return read.added() - takeOutTheGhosts(memLedger, reservesBefore);
    }

    private static boolean takesOutItsOwnGhosts(MemLedger memLedger) {
        return memLedger instanceof GhostFreeReloads ghostFree && ghostFree.takesOutGhosts();
    }

    /** L2b item 3 (S6): main's reload, its failures said, not the deprecated count. */
    private static ReloadResult reloaded(MemLedger memLedger) {
        ReloadResult read = memLedger.reloadNewRows();
        if (!read.isComplete()) log.error("the switch ledger's reload of new rows was partial — the rest was merged: {}", read.failures());
        return read;
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
