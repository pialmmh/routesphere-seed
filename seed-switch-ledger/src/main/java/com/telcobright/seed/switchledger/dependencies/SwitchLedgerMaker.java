package com.telcobright.seed.switchledger.dependencies;

import com.telcobright.core.cache.CacheableEntity;
import com.telcobright.memledger.api.MemLedger;
import com.telcobright.memledger.config.ReplicationConfig;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.api.ChargeReference;
import com.telcobright.seed.switchledger.api.LedgerAccount;
import com.telcobright.seed.switchledger.api.LedgerDb;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.AddTierLive;
import com.telcobright.seed.switchledger.internal.DriverDataSource;
import com.telcobright.seed.switchledger.internal.LedgerEntities;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.seed.switchledger.internal.OwnedMemLedgerPort;
import com.telcobright.seed.switchledger.internal.TierColumns;
import com.telcobright.seed.switchledger.spi.MemLedgerSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The {@code switch} ledger of a root — the default (R12: "our prepaid part is strictly based on package-account and mem-ledger cache"):
 * a MemLedger over the root's switch database ({@code switch-db}: its url, user, password by reference), holding the two entities of every
 * tier schema of the root's tree, its WAL and queue in the root's own directory ({@code <dir>/ledger}), STANDALONE; the base's ledger port
 * over it ({@link SwitchLedgers#memLedger}), which owns the MemLedger: closing the port (the engine's close) shuts it down; a reseller
 * tier created live (R10) is loaded into it ONE schema at a time, between the root's money moves ({@code AddTierLive}). Found on the
 * classpath by its kind ({@code META-INF/services}).
 *
 * <p>ONE writer per root: the MemLedger is STANDALONE, the only writer of the root's package accounts and reserves — serve each root from
 * ONE process. A second process on the same root, or another replication mode, would be a second writer of the same money (and a live
 * tier's merge, {@code NewRowsOfATier}, takes a reserve it did not write for a settled one's ghost).
 *
 * <p>The pool of the database's connections is the host's choice: the default opens one per use ({@code DriverManager}); a host passes its
 * own pooled {@code DataSource} maker.
 *
 * <p>ARCH-0117: {@code ledger.store} says where the MemLedger is — {@code embedded} (the default, all of the above) or {@code remote}:
 * the root's mem-ledger server, through the {@link MemLedgerSource} on the classpath (its words {@code address}, {@code deadline-ms},
 * {@code token-var}); the engine, the tiers' column check and this port are the same either way.
 *
 * <p>C2b: every tier must hold every column the cached rows write ({@link TierColumns}) — checked before the MemLedger is built and before
 * a tier is loaded live; a tier that lacks one is refused in words (mem-ledger's loader would pass over it, and the write-behind stop).
 */
public final class SwitchLedgerMaker implements LedgerMaker {

    /** {@code ledger.store}'s default: the MemLedger in this process. */
    public static final String EMBEDDED = "embedded";

    private final DataSources dataSources;

    /** How a root's switch database becomes a {@code DataSource}. */
    @FunctionalInterface
    public interface DataSources { javax.sql.DataSource of(RootResources.SwitchDb db, String password); }

    public SwitchLedgerMaker() { this(DriverDataSource::new); }

    public SwitchLedgerMaker(DataSources dataSources) { this.dataSources = dataSources; }

    @Override public String kind() { return RootResources.Ledger.SWITCH; }

    @Override
    public LedgerPort make(RootResources root, Context context) {
        RootResources.SwitchDb db = requireTheDatabase(root);
        Set<String> tiers = tierSchemasOf(root, context);
        LedgerDbSettings ledgerDb = ledgerDbOf(root);
        javax.sql.DataSource dataSource = dataSources.of(db, passwordOf(db, context));
        TierColumns columns = TierColumns.of(root.root(), dataSource, entityTypesOf(root, ledgerDb));
        columns.requireIn(tiers);
        Opened opened = isEmbedded(root) ? openEmbedded(root, dataSource, tiers, ledgerDb) : openFromItsStore(root, context, tiers, ledgerDb);
        return ownedPortOver(opened, root, context, tiers, columns, ledgerDb);
    }

    /** The root's MemLedger and the entities it must hold exactly (null = the two, and the charges' references when said). */
    private record Opened(MemLedger memLedger, Set<String> configured) {}

    /** The embedded store (the default): a MemLedger in this process over the root's switch database, its WAL and queue in the root's directory. */
    private static Opened openEmbedded(RootResources root, javax.sql.DataSource dataSource, Set<String> tiers, LedgerDbSettings ledgerDb) {
        var builder = MemLedger.builder()
            .dataSource(dataSource)
            .databases(tiers.toArray(String[]::new))
            .replicationMode(ReplicationConfig.Mode.STANDALONE);    // P1-H: ONE writer per root — a second process or mode = two writers of the same money (README)
        Set<String> configured = ledgerDb == null ? null : LedgerDb.register(builder, ledgerDb);
        if (ledgerDb == null) registerTheTwo(builder, root);
        return new Opened(builder.build(), configured);
    }

    /**
     * ARCH-0117 · {@code ledger.store: remote} (any word but {@code embedded}): the MemLedger is elsewhere — the root's mem-ledger server —
     * and comes from the {@link MemLedgerSource} that answers the word, found on the classpath. The kind stays {@code switch}: the engine
     * over it is the same. The source reads its own words ({@code address}, {@code deadline-ms}, {@code token-var}) and holds the same
     * entities by the same names; nothing opens in the root's directory.
     */
    private static Opened openFromItsStore(RootResources root, Context context, Set<String> tiers, LedgerDbSettings ledgerDb) {
        MemLedgerSource source = sourceOf(storeOf(root), root);
        Map<String, Class<? extends CacheableEntity<?, ?>>> entities = entitiesByNameOf(root, ledgerDb);
        MemLedger memLedger = source.open(new MemLedgerSource.Request(root.root(), root.ledger()::word, entities, tiers, context.environment()));
        return new Opened(memLedger, ledgerDb == null ? null : entities.keySet());
    }

    /** The engine over the MemLedger, owning it: a build that fails closes what was opened. */
    private LedgerPort ownedPortOver(Opened opened, RootResources root, Context context, Set<String> tiers, TierColumns columns, LedgerDbSettings ledgerDb) {
        MemLedger memLedger = opened.memLedger();
        try {
            MemLedgerPort port = (MemLedgerPort) SwitchLedgers.over(memLedger, SwitchLedgerSettings.standard(), context.clock(), opened.configured()).port();
            return new OwnedMemLedgerPort(port, memLedger, tiers, checkedFirst(columns, AddTierLive.of(memLedger)), mergeSpacingMsOf(root), ledgerDb == null ? null : LedgerDb.of(ledgerDb));
        } catch (RuntimeException e) {
            memLedger.shutdown();
            throw e;
        }
    }

    /** {@code ledger.store}: not said or {@code embedded} = in this process. */
    private static boolean isEmbedded(RootResources root) { return EMBEDDED.equals(storeOf(root)); }

    private static String storeOf(RootResources root) {
        String said = root.ledger().word("store");
        return said == null || said.isBlank() ? EMBEDDED : said.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static MemLedgerSource sourceOf(String store, RootResources root) {
        List<String> known = new java.util.ArrayList<>(List.of(EMBEDDED));
        for (MemLedgerSource source : java.util.ServiceLoader.load(MemLedgerSource.class)) {
            if (store.equals(source.store())) return source;
            known.add(source.store());
        }
        throw new IllegalStateException("the root '" + root.root() + "' says ledger.store: " + store + ", which nothing on the classpath answers (known: " + known
            + "; 'remote' is seed-memledger-remote's)");
    }

    /** The entities the root's ledger caches by the names they are registered under: its ledger DB's, or the two (+ the charges' references when said). */
    private static Map<String, Class<? extends CacheableEntity<?, ?>>> entitiesByNameOf(RootResources root, LedgerDbSettings ledgerDb) {
        if (ledgerDb == null) return theTwoOf(root);
        Map<String, Class<? extends CacheableEntity<?, ?>>> named = new LinkedHashMap<>();
        LedgerEntities.named(ledgerDb).forEach((name, known) -> named.put(name, known.type()));
        return named;
    }

    /** A tier created live is loaded only when it holds every column the ledger writes (refused in words, as at the start). */
    private static Predicate<String> checkedFirst(TierColumns columns, AddTierLive live) {
        return tierDb -> {
            columns.requireIn(List.of(tierDb));
            return live.load(tierDb);
        };
    }

    /** The entity types the root's ledger caches: its ledger DB's, or the two (+ the charges' references when said). */
    private static List<Class<?>> entityTypesOf(RootResources root, LedgerDbSettings ledgerDb) {
        if (ledgerDb != null) return LedgerEntities.named(ledgerDb).values().stream().<Class<?>>map(LedgerEntities.Known::type).toList();
        return List.copyOf(theTwoOf(root).values());
    }

    /** Before L2b's ledger DB: the two tables (+ the charges' references when said), under the root's {@code <dir>/ledger}. */
    private static void registerTheTwo(com.telcobright.memledger.api.MemLedgerBuilder builder, RootResources root) {
        theTwoOf(root).forEach(builder::registerEntity);
        builder.walPath(root.dir().resolve("ledger").resolve("wal").toString()).queuePath(root.dir().resolve("ledger").resolve("queue").toString());
    }

    /** The two entities by the names the ledger knows them (+ the charges' references when said). */
    private static Map<String, Class<? extends CacheableEntity<?, ?>>> theTwoOf(RootResources root) {
        Map<String, Class<? extends CacheableEntity<?, ?>>> two = new LinkedHashMap<>();
        two.put(Books.ACCOUNT, LedgerAccount.class);
        two.put(Books.RESERVE, PackageAccountReserve.class);
        if (keepsChargeReferences(root)) two.put(Books.CHARGE, ChargeReference.class);
        return two;
    }

    /**
     * L2b item 3 · The root's ledger DB, when its ledger names its entities: {@code ledger.entities} (names or table prefixes, comma-separated:
     * {@code package}), {@code ledger.cache-only} (tables loaded at the start only: {@code packageaccountreserve}), the reload spacing
     * {@code ledger.merge-spacing-sec}; the tiers are the tree's. Not said: null — the two tables, as before.
     */
    private static LedgerDbSettings ledgerDbOf(RootResources root) {
        List<String> entities = listOf(root.ledger().word("entities"));
        if (entities.isEmpty()) return null;
        Path dir = root.dir().resolve("ledger");
        return new LedgerDbSettings(entities, listOf(root.ledger().word("cache-only")), dir.resolve("wal"), dir.resolve("queue"), mergeSpacingMsOf(root));
    }

    private static List<String> listOf(String words) {
        if (words == null || words.isBlank()) return List.of();
        return Arrays.stream(words.split("[,\\s]+")).filter(w -> !w.isBlank()).toList();
    }

    /**
     * L2b 2b · {@code ledger.charge-references = on}: every final charge carries its durable reference row ({@code packageaccountcharge} in
     * every tier schema — its DDL in the round's report; nothing here creates it). Not said: the charges are idempotent in memory only.
     */
    private static boolean keepsChargeReferences(RootResources root) {
        String said = root.ledger().word("charge-references");
        return said != null && (said.trim().equalsIgnoreCase("on") || said.trim().equalsIgnoreCase("true"));
    }

    /** P1-J · {@code ledger.merge-spacing-sec}: the least time between two merges of the root's new rows; 5 s when not said. */
    private static long mergeSpacingMsOf(RootResources root) {
        String said = root.ledger().word("merge-spacing-sec");
        if (said == null || said.isBlank()) return OwnedMemLedgerPort.DEFAULT_MERGE_SPACING_MS;
        long sec = Long.parseLong(said.trim());
        if (sec <= 0) throw new IllegalStateException("the root '" + root.root() + "': ledger.merge-spacing-sec must be above 0 (was " + sec + ")");
        return sec * 1000;
    }

    /**
     * P1-K · The login's password from the PROCESS environment only — switch-db's and the ledger's own ({@code ledger.db}) alike: the
     * owner's ruling of 2026-10-24 ("Environment only"), which re-affirms his rule of 2026-09-24 (secreteer fills the box's environment
     * file). The bootstrap's {@code secrets} are never read: a variable not in the environment refuses the root's ledger in words naming
     * it, never a value — and nothing is connected.
     */
    private static String passwordOf(RootResources.SwitchDb login, Context context) {
        return login.passwordFromTheEnvironment(context.environment());
    }

    /** The login the ledger connects with — its own ({@code ledger.db}) or switch-db's — refused in words without its database. */
    private static RootResources.SwitchDb requireTheDatabase(RootResources root) {
        RootResources.SwitchDb db = root.ledgerLogin();
        if (db == null || db.url() == null) throw new IllegalStateException("the root '" + root.root() + "' has the switch ledger but no switch-db.url: its package accounts have no database");
        return db;
    }

    /** Every tier schema of the root's tree: the root and each reseller tier under it (each with its own two tables). */
    private static Set<String> tierSchemasOf(RootResources root, Context context) {
        RootTree tree = context.trees().snapshot(root.root())
            .orElseThrow(() -> new IllegalStateException("the root '" + root.root() + "' has no tree: its ledger would not know its tiers' schemas"));
        Set<String> schemas = new LinkedHashSet<>();
        schemas.add(tree.rootDbName());
        if (tree.tree().getTenantIndex() != null) for (Tenant t : tree.tree().getTenantIndex().values()) schemas.add(t.getDbName());
        return schemas;
    }
}
