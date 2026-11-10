package com.telcobright.seed.switchledger.spi;

import com.telcobright.core.cache.CacheableEntity;
import com.telcobright.memledger.api.MemLedger;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * ARCH-0117 · Where a root's MemLedger comes from when its ledger block says {@code store:} something other than {@code embedded}
 * (the owner 2026-11-09: mem-ledger as a database server — the engine stays in the application, only the MemLedger behind it is
 * elsewhere). Found on the classpath by {@code ServiceLoader} ({@code META-INF/services}), by its word: seed-memledger-remote answers
 * {@code remote}. This module names no wire: it keeps no gRPC and no protobuf dependency.
 *
 * <p>What comes back is a {@link MemLedger} the engine uses as it uses the embedded one — the eight methods it calls
 * ({@code getEntity}, {@code getAllEntities}, {@code execCrud(WALEntryBatch)}, {@code getStats().getCacheStatistics()},
 * {@code getRegisteredDatabases}, {@code reloadNewRows}, {@code addDatabase}, {@code shutdown}); {@code shutdown} ends the
 * application's use of it, never the ledger itself.
 */
public interface MemLedgerSource {

    /** The word of {@code ledger.store} this source answers ({@code remote}). */
    String store();

    /** The root's MemLedger, open and holding every tier named; refused in words when a word it needs is missing or the ledger does not answer. */
    MemLedger open(Request request);

    /**
     * What the maker knows of the root's ledger.
     *
     * @param root        the root's name (for the words of a refusal)
     * @param word        the ledger block's words by key ({@code address}, {@code deadline-ms}, {@code token-var} …); null = not said
     * @param entities    the entities the root's ledger caches, by the name they are registered under, with their classes
     * @param tiers       every tier schema of the root's tree, the root's own first
     * @param environment the process environment by variable name (a secret is read by the NAME a word gives, never written)
     */
    record Request(String root, Function<String, String> word, Map<String, Class<? extends CacheableEntity<?, ?>>> entities, Set<String> tiers,
                   Function<String, String> environment) {}
}
