package com.telcobright.seed.switchledger.dependencies;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * L2b item 3 · The ledger DB of a root, a standard DB of the seed (design §5.2, §9.3) — VALUES only, as the application reads them from its
 * own config (R0: the seed reads no file). The TIERS are never here: every tier of the root's tree is cached, new ones come from the
 * signals ({@code tenant_created}, the doorbell).
 *
 * @param entities        the entity names or table-name PREFIXES to cache ({@code package} = every known money table:
 *                        packageaccount, packageaccountreserve, packageaccountcharge, packagepurchase)
 * @param cacheOnly       the tables written only by this cache: loaded at the start (and with a tier added), never re-read by a reload — no
 *                        ghosts of rows the cache already deleted ({@code packageaccountreserve})
 * @param walDir          the WAL's directory (the root's {@code <dir>/ledger/wal})
 * @param queueDir        the queue's directory (the root's {@code <dir>/ledger/queue})
 * @param reloadSpacingMs the least time between two reloads of new rows: a burst of doorbells is one reload at once and one at the end
 */
public record LedgerDbSettings(List<String> entities, List<String> cacheOnly, Path walDir, Path queueDir, long reloadSpacingMs) {

    public LedgerDbSettings {
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        cacheOnly = cacheOnly == null ? List.of() : List.copyOf(cacheOnly);
        Objects.requireNonNull(walDir, "walDir");
        Objects.requireNonNull(queueDir, "queueDir");
        if (entities.isEmpty()) throw new IllegalArgumentException("a ledger DB caches at least one entity: name its entities or their table prefix");
        if (reloadSpacingMs <= 0) throw new IllegalArgumentException("reloadSpacingMs must be above 0 (was " + reloadSpacingMs + ")");
    }

    /** The switch ledger as before L2b: its two tables, re-read by every reload, under {@code <rootDir>/ledger}, 5 s between reloads. */
    public static LedgerDbSettings twoTables(Path rootDir) {
        return new LedgerDbSettings(List.of("packageaccount", "packageaccountreserve"), List.of(), rootDir.resolve("ledger").resolve("wal"),
            rootDir.resolve("ledger").resolve("queue"), 5_000);
    }
}
