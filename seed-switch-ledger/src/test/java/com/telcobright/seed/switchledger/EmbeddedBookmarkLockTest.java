package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerMaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private LedgerPort openEmbedded() {
        RootResources btcl = new RootResources("btcl", new RootResources.SwitchDb(PgTiersLab.url(), "btcl", PgTiersLab.user(), null),
            new RootResources.KafkaRoad("10.10.188.2:9092", 1, (short) 1), null, new RootResources.Ledger(RootResources.Ledger.SWITCH, Map.of()), dir);
        Deployment d = new Deployment("platform", "ad-sphere", "bed", List.of("btcl"), RoutesphereSettings.defaults(), ZoneId.of("Asia/Dhaka"),
            List.of(), Map.of(), "10.10.188.2:9092", null, Map.of(), "e1");
        return new SwitchLedgerMaker().make(btcl, new LedgerMaker.Context(d, trees, Clock.systemUTC(), name -> null));
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
