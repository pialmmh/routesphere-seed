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
import com.telcobright.seed.switchledger.internal.Books;
import com.telcobright.seed.switchledger.internal.DriverDataSource;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The {@code switch} ledger of a root — the default (R12: "our prepaid part is strictly based on package-account and mem-ledger cache"):
 * a MemLedger over the root's switch database ({@code switch-db}: its url, user, password by reference), holding the two entities of every
 * tier schema of the root's tree, its WAL and queue in the root's own directory ({@code <dir>/ledger}), STANDALONE; the base's ledger port
 * over it ({@link SwitchLedgers#memLedger}). Found on the classpath by its kind ({@code META-INF/services}).
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
        MemLedger memLedger = MemLedger.builder()
            .dataSource(dataSources.of(db, db.password(context.environment(), context.deployment().secrets())))
            .databases(tierSchemasOf(root, context).toArray(String[]::new))
            .registerEntity(Books.ACCOUNT, PackageAccount.class)
            .registerEntity(Books.RESERVE, PackageAccountReserve.class)
            .walPath(root.dir().resolve("ledger").resolve("wal").toString())
            .queuePath(root.dir().resolve("ledger").resolve("queue").toString())
            .replicationMode(ReplicationConfig.Mode.STANDALONE)
            .build();
        return SwitchLedgers.memLedger(memLedger, SwitchLedgerSettings.standard(), context.clock());
    }

    private static RootResources.SwitchDb requireTheDatabase(RootResources root) {
        RootResources.SwitchDb db = root.switchDb();
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
