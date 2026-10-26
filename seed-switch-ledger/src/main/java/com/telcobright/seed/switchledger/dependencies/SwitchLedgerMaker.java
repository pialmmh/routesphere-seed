package com.telcobright.seed.switchledger.dependencies;

import com.telcobright.memledger.api.MemLedger;
import com.telcobright.memledger.config.ReplicationConfig;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccountReserve;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.switchledger.api.ChargeReference;
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.AddTierLive;
import com.telcobright.seed.switchledger.internal.DriverDataSource;
import com.telcobright.seed.switchledger.internal.MemLedgerPort;
import com.telcobright.seed.switchledger.internal.OwnedMemLedgerPort;

import java.util.LinkedHashSet;
import java.util.Set;

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
 */
public final class SwitchLedgerMaker implements LedgerMaker {

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
        var builder = MemLedger.builder()
            .dataSource(dataSources.of(db, passwordOf(db, context)))
            .databases(tiers.toArray(String[]::new))
            .registerEntity(Books.ACCOUNT, PackageAccount.class)
            .registerEntity(Books.RESERVE, PackageAccountReserve.class)
            .walPath(root.dir().resolve("ledger").resolve("wal").toString())
            .queuePath(root.dir().resolve("ledger").resolve("queue").toString())
            .replicationMode(ReplicationConfig.Mode.STANDALONE);    // P1-H: ONE writer per root — a second process or mode = two writers of the same money (README)
        if (keepsChargeReferences(root)) builder.registerEntity(Books.CHARGE, ChargeReference.class);
        MemLedger memLedger = builder.build();
        MemLedgerPort port = (MemLedgerPort) SwitchLedgers.memLedger(memLedger, SwitchLedgerSettings.standard(), context.clock());
        return new OwnedMemLedgerPort(port, memLedger, tiers, AddTierLive.of(memLedger)::load, mergeSpacingMsOf(root));
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
