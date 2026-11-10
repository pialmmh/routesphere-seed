package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerMaker;
import com.telcobright.seed.switchledger.internal.DriverDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Item (b) of the remote-fixes round · a root whose ledger says no store opens an EMBEDDED MemLedger — and on the box its WAL directory and
 * its bookmark row ({@code <root's schema>.wal_consumer_offset}) are recall-db's (zero-move: same directory, same user). On PostgreSQL the
 * embedded store takes recall-db's OWN bookmark lock ({@code recall-db-bookmark:<first schema>}, hashtext, a session advisory lock) for
 * its life: held elsewhere, the open is refused in words; free, it opens and holds it until its shutdown. The row guarded is the root's
 * schema — the FIRST of the ledger's tiers, as recall-db's start list puts it first and as mem-ledger keeps its bookmark there.
 */
class EmbeddedBookmarkLockTest {

    private static final List<String> TIERS = List.of("btcl", "btcl_res_5");

    @TempDir Path dir;
    private final TenantLookup trees = TenantLookup.of(new TenantTreeBuilder().root("btcl").partner(1, "client", PartnerType.CUSTOMER)
        .partner(5, "Res5", PartnerType.RESELLER).and().tenant("btcl_res_5", "btcl").partner(501, "Res5 client", PartnerType.CUSTOMER).and().build());

    @BeforeEach
    void aFreshLab() throws SQLException {
        assumeTrue(PgTiersLab.absentBecause() == null, PgTiersLab.absentBecause());
        PgTiersLab.freshTiers(TIERS);
    }

    @Test
    void aBookmarkRowRecallDbHolds_refusesTheEmbeddedOpenInWords_andOnceFreeTheLedgerOpensAndHoldsItForItsLife() throws Exception {
        try (Connection recallDb = PgTiersLab.connect()) {
            assertThat(PgTiersLab.takeTheBookmarkLockAsRecallDb(recallDb, "btcl")).as("recall-db's bookmark lock, held by a plain session").isTrue();
            assertThatThrownBy(this::openEmbedded)
                .hasMessageContaining("the bookmark row btcl.wal_consumer_offset is held by another ledger — recall-db? — this ledger does not open");
        }                                                                                 // the session ends: its advisory lock goes with it

        try (AutoCloseable ledger = (AutoCloseable) openEmbedded(); Connection recallDb = PgTiersLab.connect()) {
            assertThat(PgTiersLab.takeTheBookmarkLockAsRecallDb(recallDb, "btcl")).as("the open ledger holds it: recall-db would refuse to start").isFalse();
        }
        try (Connection recallDb = PgTiersLab.connect()) {
            assertThat(PgTiersLab.takeTheBookmarkLockAsRecallDb(recallDb, "btcl")).as("released at the ledger's shutdown").isTrue();
        }
    }

    @Test
    void theRowGuarded_isTheRootsSchema_theFirstTier_whereMemLedgerKeepsItsBookmark() throws Exception {
        try (Connection other = PgTiersLab.connect()) {
            assertThat(PgTiersLab.takeTheBookmarkLockAsRecallDb(other, "btcl_res_5")).as("a reseller tier's name: not a bookmark row").isTrue();
            try (AutoCloseable ledger = (AutoCloseable) openEmbedded()) {
                assertThat(schemasHolding("wal_consumer_offset")).as("mem-ledger's bookmark row is in the first tier, the root's").containsExactly("btcl");
            }
        }
    }

    /**
     * The remote-fixes closing (the review of item (b)): a MemLedger build that fails with an ERROR, not an exception — e.g. Chronicle's
     * ExceptionInInitializerError in a JVM without its --add-opens — gives the lock back too: the next open in this JVM, or a recall-db,
     * is not refused for a lock nobody uses ("held by another ledger — recall-db?" would name the wrong cause).
     */
    @Test
    void aBuildThatFailsWithAnError_givesTheLockBack() throws Exception {
        assertThatThrownBy(() -> openEmbeddedOver(BuildFailsWithAnError::new)).isInstanceOf(ExceptionInInitializerError.class);

        try (Connection recallDb = PgTiersLab.connect()) {
            assertThat(PgTiersLab.takeTheBookmarkLockAsRecallDb(recallDb, "btcl")).as("given back: the failed build holds nothing").isTrue();
        }
    }

    /**
     * The remote-fixes closing (the review of item (b)): mem-ledger's shutdown() waits 5 s for its WAL consumer and then returns even if the
     * consumer is still inside a batch — the batch that moves the bookmark row. The lock is given back only once that consumer has ended:
     * while it still writes, recall-db cannot take the row.
     */
    @Test
    void aConsumerStillWritingAfterTheShutdown_keepsTheLockUntilItEnds() throws Exception {
        try (Connection c = PgTiersLab.connect(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO btcl.packageaccount (id_packageaccount, balanceAfter, balanceBefore, id_PackagePurchase, lastAmount, name, uom)"
                + " VALUES (11, 100, 100, 1, 0, 'cash', 'BDT')");
        }
        LedgerPort ledger = openEmbedded();
        try (Connection blocker = PgTiersLab.connect()) {
            blocker.setAutoCommit(false);
            blocker.createStatement().execute("LOCK TABLE btcl.packageaccount IN ACCESS EXCLUSIVE MODE");
            assertThat(ledger.reserve(levelOf(11), BigDecimal.ONE, "s-1#L0")).as("held in the cache; its WAL record waits for the consumer").isPresent();
            PgTiersLab.await("the WAL consumer's batch waits on the locked table", () -> sessionsWaitingOnALock() > 0);

            ((AutoCloseable) ledger).close();                                        // shutdown() waits 5 s, then returns: the consumer still runs

            try (Connection recallDb = PgTiersLab.connect()) {
                assertThat(PgTiersLab.takeTheBookmarkLockAsRecallDb(recallDb, "btcl")).as("kept while the consumer still writes the bookmark row").isFalse();
            }
            blocker.rollback();
        }
        PgTiersLab.await("given back once the consumer ended", () -> {
            try (Connection recallDb = PgTiersLab.connect()) {
                return PgTiersLab.takeTheBookmarkLockAsRecallDb(recallDb, "btcl");
            }
        });
    }

    private static LevelAdmission levelOf(long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(1);
        PackageAccount account = new PackageAccount();
        account.setId(accountId);
        account.setUom("BDT");
        LevelAdmission level = new LevelAdmission(0, new Tenant("btcl"), partner, account);
        level.setRate(BigDecimal.ONE);
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    private static int sessionsWaitingOnALock() throws SQLException {
        try (Connection c = PgTiersLab.connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname = '" + PgTiersLab.DATABASE + "' AND wait_event_type = 'Lock'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private LedgerPort openEmbedded() { return openEmbeddedOver(UnaryOperator.identity()); }

    private LedgerPort openEmbeddedOver(UnaryOperator<DataSource> dataSource) {
        RootResources btcl = new RootResources("btcl", new RootResources.SwitchDb(PgTiersLab.url(), "btcl", PgTiersLab.user(), null),
            new RootResources.KafkaRoad("10.10.188.2:9092", 1, (short) 1), null, new RootResources.Ledger(RootResources.Ledger.SWITCH, Map.of()), dir);
        Deployment d = new Deployment("platform", "ad-sphere", "bed", List.of("btcl"), RoutesphereSettings.defaults(), ZoneId.of("Asia/Dhaka"),
            List.of(), Map.of(), "10.10.188.2:9092", null, Map.of(), "e1");
        return new SwitchLedgerMaker((db, password) -> dataSource.apply(new DriverDataSource(db, password)))
            .make(btcl, new LedgerMaker.Context(d, trees, Clock.systemUTC(), name -> null));
    }

    /** The root's database, except that mem-ledger's own build cannot use it: an Error, as a class that cannot initialize throws. */
    private record BuildFailsWithAnError(DataSource real) implements DataSource {
        @Override public Connection getConnection() throws java.sql.SQLException { refuseTheBuild(); return real.getConnection(); }
        @Override public Connection getConnection(String user, String password) throws java.sql.SQLException { refuseTheBuild(); return real.getConnection(user, password); }
        @Override public PrintWriter getLogWriter() throws java.sql.SQLException { return real.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter out) throws java.sql.SQLException { real.setLogWriter(out); }
        @Override public void setLoginTimeout(int seconds) throws java.sql.SQLException { real.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws java.sql.SQLException { return real.getLoginTimeout(); }
        @Override public Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { return real.getParentLogger(); }
        @Override public <T> T unwrap(Class<T> iface) throws java.sql.SQLException { return real.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException { return real.isWrapperFor(iface); }

        private static void refuseTheBuild() {
            boolean byMemLedger = StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> f.getClassName().startsWith("com.telcobright.memledger.")));
            if (byMemLedger) throw new ExceptionInInitializerError("the lab: a class mem-ledger's build needs could not initialize");
        }
    }

    private static List<String> schemasHolding(String table) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (Connection c = PgTiersLab.connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT table_schema FROM information_schema.tables WHERE table_name = '" + table + "' ORDER BY 1")) {
            while (rs.next()) schemas.add(rs.getString(1));
        }
        return schemas;
    }
}
