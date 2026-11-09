package com.telcobright.seed.switchledgerremote.internal;

import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.spi.LedgerPort.ItemCatalog;
import com.telcobright.seed.switchledgerremote.wire.CatalogSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The client's catalog: one immutable snapshot per tier, fetched at the start (every tier the service's Health names), again on each ring
 * of the doorbell the application already hears and when a tier is added — after that {@code catalogOf(tier)} is a map get (§15: no
 * lookup in the hot path). A tier asked for before any snapshot of it is fetched ONCE, bounded by the deadline; a fetch that fails leaves
 * the last snapshot (or none) and is said once per tier, never thrown: the walk then matches by unit, as before §15.
 */
public final class CatalogCache {

    private static final Logger log = LoggerFactory.getLogger(CatalogCache.class);

    private final Function<String, CatalogSnapshot> fetch;
    private final Map<String, Optional<ItemCatalog>> byTier = new ConcurrentHashMap<>();

    /** @param fetch the Catalog call of a tier, bounded by the client's deadline */
    public CatalogCache(Function<String, CatalogSnapshot> fetch) { this.fetch = fetch; }

    /** The tier's catalog: the snapshot held, else fetched once now. */
    public Optional<ItemCatalog> of(String db) {
        if (db == null) return Optional.empty();
        Optional<ItemCatalog> known = byTier.get(db);
        return known != null ? known : refresh(db);
    }

    /** The tier's snapshot fetched again; on a failure the one held stays. */
    public Optional<ItemCatalog> refresh(String db) {
        try {
            Optional<ItemCatalog> made = WireCatalog.catalogOf(fetch.apply(db));
            byTier.put(db, made);
            return made;
        } catch (RuntimeException e) {
            log.warn("switch-ledger client: the catalog of tier {} was not fetched ({}) — the snapshot held stays", db, LogText.failureText(e));
            return byTier.getOrDefault(db, Optional.empty());
        }
    }

    /** Every tier named and every tier held, fetched again (a doorbell's ring). */
    public void refreshAll(Collection<String> tiers) {
        Set<String> all = new LinkedHashSet<>(tiers);
        all.addAll(byTier.keySet());
        all.forEach(this::refresh);
    }

    /** The tiers a snapshot is held for. */
    public Set<String> tiers() { return Set.copyOf(byTier.keySet()); }
}
