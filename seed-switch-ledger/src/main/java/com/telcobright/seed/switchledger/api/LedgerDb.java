package com.telcobright.seed.switchledger.api;

import com.telcobright.memledger.api.MemLedgerBuilder;
import com.telcobright.seed.switchledger.dependencies.LedgerDbSettings;
import com.telcobright.seed.switchledger.internal.LedgerEntities;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * L2b item 3 · The ledger DB, a standard DB of the seed (design §5.2, §9.3; the owner 2026-10-26: "which entities to load in the cache for
 * crud shall be configurable, but shall match entity name prefix … signal the cache … then the cache shall reload the new entities"):
 * <ul>
 *   <li>{@link #register} — the entities the settings name (names or table prefixes) registered in the MemLedger being built; a cache-only
 *       table loaded at the start only (never re-read: no ghosts); every tier of the root's tree, never a configured list; every key the
 *       database would generate (the account's) named as minted by the caller (L2c: the purchase's accounts keep their minted ids);</li>
 *   <li>{@link #decide} — what a ring of the doorbell asks of the cache: an INSERT on a cached table (not cache-only) → a reload of the new
 *       rows (coalesced by the caller); {@code *:SNAPSHOT} or {@code what} = start, stream-open, reload, migrate-all, nightly → the full
 *       catch-up (the same reload, every table); a tier the ring names that the ledger lacks → that tier loaded (a lost
 *       {@code tenant_created}); UPDATE or DELETE of its own tables, or a table it does not cache → nothing (the ledger is their writer).</li>
 * </ul>
 * The reload is mem-ledger's {@code reloadNewRows()} WHOLE (add-only, above each table's mark): the targeted per-(tier, table) reload
 * ({@code MemLedgerImpl.reloadNewRowsOf}, private on main 6523614) is mem-ledger's follow-up, named in the round's report.
 */
public final class LedgerDb {

    /** The {@code what} words that ask the full catch-up. */
    public static final Set<String> CATCH_UP = Set.of("start", "stream-open", "reload", "migrate-all", "nightly");

    /** What a ring asks: reload the new rows (the catch-up is the same reload) and load the tiers the ledger lacks. */
    public record Decision(boolean reload, Set<String> tiersToAdd) {
        public static final Decision NOTHING = new Decision(false, Set.of());
        public boolean nothing() { return !reload && tiersToAdd.isEmpty(); }
    }

    private final Set<String> cached;
    private final Set<String> cacheOnly;

    private LedgerDb(Set<String> cached, Set<String> cacheOnly) {
        this.cached = cached;
        this.cacheOnly = cacheOnly;
    }

    /** The ledger DB of these settings: the tables it caches (refused in words when a word names nothing). */
    public static LedgerDb of(LedgerDbSettings settings) {
        Set<String> cacheOnly = new LinkedHashSet<>();
        settings.cacheOnly().forEach(t -> cacheOnly.add(t.toLowerCase(Locale.ROOT)));
        return new LedgerDb(LedgerEntities.tablesOf(LedgerEntities.named(settings)), cacheOnly);
    }

    /** The configured entities registered in the MemLedger being built; answers their names (what the ledger must then hold exactly). */
    public static Set<String> register(MemLedgerBuilder builder, LedgerDbSettings settings) {
        Map<String, LedgerEntities.Known> named = LedgerEntities.named(settings);
        for (LedgerEntities.Known k : named.values()) {
            boolean startOnly = settings.cacheOnly().stream().anyMatch(t -> t.equalsIgnoreCase(k.table()));
            if (startOnly) builder.registerEntityLoadedAtStartOnly(k.name(), k.type());
            else builder.registerEntity(k.name(), k.type());
        }
        nameTheKeysTheSeedMints(builder, named.values());
        builder.walPath(settings.walDir().toString()).queuePath(settings.queueDir().toString());
        return named.keySet();
    }

    /**
     * L2c (mem-ledger L1b, the owner's option A) · Every registered entity whose key the database would generate — the account — is named as
     * minted by the caller: the purchase mints its accounts' ids (the application's {@code IdSource}, bucket-next) and the ledger writes
     * them on every INSERT, so the database's ids are the cache's. The rule that comes with it: in a lane on this ledger nothing else creates
     * accounts in those tables by the database's counter (one creator per table, S7).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void nameTheKeysTheSeedMints(MemLedgerBuilder builder, Collection<LedgerEntities.Known> entities) {
        List<Class<? extends com.telcobright.core.cache.CacheableEntity>> minted = LedgerEntities.keysTheDatabaseWouldGenerate(entities);
        if (!minted.isEmpty()) builder.keysMintedByTheCaller(minted.toArray(new Class[0]));
    }

    /** The tables cached (lower case). */
    public Set<String> tables() { return cached; }

    /** What this ring asks of a ledger that holds {@code tiersHeld}. */
    public Decision decide(Doorbell ring, Collection<String> tiersHeld) {
        if (CATCH_UP.contains(ring.what().toLowerCase(Locale.ROOT))) return new Decision(true, Set.of());
        boolean reload = false;
        Set<String> lacking = new LinkedHashSet<>();
        for (Doorbell.Change c : ring.readChanges()) {
            if ("SNAPSHOT".equals(c.operation())) reload = true;
            if ("*".equals(c.tier())) continue;
            if (cached.contains(c.table()) && !tiersHeld.contains(c.tier())) lacking.add(c.tier());
            else if ("INSERT".equals(c.operation()) && cached.contains(c.table()) && !cacheOnly.contains(c.table())) reload = true;
        }
        return reload || !lacking.isEmpty() ? new Decision(reload, lacking) : Decision.NOTHING;
    }
}
