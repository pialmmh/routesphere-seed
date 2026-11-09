package com.telcobright.seed.switchledgerremote.api;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.seed.switchledgerremote.wire.HealthReply;
import com.telcobright.seed.switchledgerremote.wire.TierHealth;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Health as design §3.3 states it: READY (the cache is UP and every tier is loaded), the WAL lag (records written and not yet persisted;
 * -1 when the ledger does not say), the tiers with {@code loaded} each, the service's version word. The client keeps the last one it
 * read: {@code tierLoaded(db)} answers from it.
 */
public record LedgerHealth(boolean ready, long walLag, Map<String, Boolean> tiers, String version) {

    public static LedgerHealth unknown() { return new LedgerHealth(false, -1, Map.of(), ""); }

    public boolean known() { return !tiers.isEmpty() || ready; }

    /** A tier the health never named is taken as loaded — the port's own default. */
    public boolean tierLoaded(String db) { return tiers.getOrDefault(db, true); }

    /** The engine's health: the MemLedger's own words plus what the owner knows of each tier. */
    public static LedgerHealth of(MemLedger ledger, Collection<String> tiers, Predicate<String> loaded, String version) {
        Map<String, Object> health = ledger.health();
        boolean up = "UP".equals(health.get("status"));
        Map<String, Boolean> byTier = new LinkedHashMap<>();
        boolean allLoaded = true;
        for (String tier : tiers) {
            boolean is = loaded.test(tier);
            byTier.put(tier, is);
            allLoaded &= is;
        }
        return new LedgerHealth(up && allLoaded, lagOf(health), Map.copyOf(byTier), version == null ? "" : version);
    }

    /** Written minus persisted where the ledger states both; -1 where it does not (f739824 states only the written side). */
    @SuppressWarnings("unchecked")
    private static long lagOf(Map<String, Object> health) {
        Object wal = health.get("wal");
        Object persistence = health.get("persistence");
        if (!(wal instanceof Map<?, ?> w) || !(persistence instanceof Map<?, ?> p)) return -1;
        Object written = ((Map<String, Object>) w).get("batchesProcessed");
        Object persisted = ((Map<String, Object>) p).get("batches_processed");
        if (!(written instanceof Number a) || !(persisted instanceof Number b)) return -1;
        return Math.max(0, a.longValue() - b.longValue());
    }

    public HealthReply toWire() {
        HealthReply.Builder out = HealthReply.newBuilder().setReady(ready).setWalLag(walLag).setVersion(version == null ? "" : version);
        tiers.forEach((db, loaded) -> out.addTiers(TierHealth.newBuilder().setDbName(db).setLoaded(loaded)));
        return out.build();
    }

    public static LedgerHealth of(HealthReply reply) {
        Map<String, Boolean> byTier = new LinkedHashMap<>();
        for (TierHealth t : reply.getTiersList()) byTier.put(t.getDbName(), t.getLoaded());
        return new LedgerHealth(reply.getReady(), reply.getWalLag(), Map.copyOf(byTier), reply.getVersion());
    }
}
