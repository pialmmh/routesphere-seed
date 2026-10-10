package com.telcobright.seed.switchledger;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.memledger.api.MemLedgerStats;
import com.telcobright.memledger.api.request.CrudRequest;
import com.telcobright.memledger.api.request.GetEntityRequest;
import com.telcobright.memledger.api.response.EntityResponse;
import com.telcobright.core.cache.CacheableEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A MemLedger that reports only PackageAccount in its contents (the stats of the real one, with the reserve entity hidden). */
final class OnlyAccounts implements MemLedger {

    private final MemLedger real;

    OnlyAccounts(LedgerLab lab) { this.real = lab.ledger(); }

    @Override
    @SuppressWarnings("unchecked")
    public MemLedgerStats getStats() {
        MemLedgerStats stats = real.getStats();
        Map<String, Object> cache = new HashMap<>(stats.getCacheStatistics());
        Map<String, Map<String, Integer>> contents = new HashMap<>();
        ((Map<String, Map<String, Integer>>) cache.get("cache_contents")).forEach((db, entities) -> {
            Map<String, Integer> only = new HashMap<>(entities);
            only.remove("PackageAccountReserve");
            contents.put(db, only);
        });
        cache.put("cache_contents", contents);
        stats.setCacheStatistics(cache);
        return stats;
    }

    @Override public boolean isInitialized() { return real.isInitialized(); }
    @Override public <K, E extends CacheableEntity<K, ?>> E getEntity(String d, String e, K id) { return real.getEntity(d, e, id); }
    @Override public EntityResponse getEntity(GetEntityRequest r) { return real.getEntity(r); }
    @Override public EntityResponse execCrud(CrudRequest r) { return real.execCrud(r); }
    @Override public <K, E extends CacheableEntity<K, ?>> boolean insert(String d, String e, E entity) { return real.insert(d, e, entity); }
    @Override public <K, E extends CacheableEntity<K, ?>> boolean update(String d, String e, E entity) { return real.update(d, e, entity); }
    @Override public <K, D, E extends CacheableEntity<K, D>> E updateWithDelta(String d, String e, K id, D delta) { return real.updateWithDelta(d, e, id, delta); }
    @Override public <K> boolean delete(String d, String e, K id) { return real.delete(d, e, id); }
    @Override public <K, E extends CacheableEntity<K, ?>> Map<K, E> getAllEntities(String d, String e) { return real.getAllEntities(d, e); }
    @Override public List<String> getRegisteredDatabases() { return real.getRegisteredDatabases(); }
    @Override public Map<String, Object> health() { return real.health(); }
    @Override public void shutdown() { }
    @Override public void reload() { real.reload(); }
    @Override public List<String> reloadDatabases() { return real.reloadDatabases(); }
    @Override public int reloadNewEntities() { return real.reloadNewEntities(); }
}
