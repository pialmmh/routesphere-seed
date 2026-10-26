package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.dependencies.KitMakers;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.SessionJournals;
import com.telcobright.seed.sessionflow.internal.PerRootLedger;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.LiveTrees;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.spi.TreeGate;
import com.telcobright.seed.sessionflow.testkit.FakeKafka;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerMaker;
import com.telcobright.seed.switchledger.internal.OwnedMemLedgerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2b item 3 · the kit from the bootstrap, its root's ledger a LEDGER DB ({@code ledger.entities}, {@code ledger.cache-only}): the
 * doorbell's words reach it through the trees ({@code LiveTrees.onDoorbellWords}, fed by seed-context's doorbell consumer) — an INSERT on
 * a cached table makes the new account pay; a tree's reload alone, and the ledger's own tables' UPDATE, re-read nothing (S2).
 */
class DoorbellWordsReachTheLedgerDbTest {

    @TempDir Path dir;
    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("btcl_res_5").account("btcl", 1, 10, "BDT", "100.00");
    private final WordTrees trees = new WordTrees(new TenantTreeBuilder().root("btcl").partner(1, "client", PartnerType.CUSTOMER)
        .partner(5, "Res5", PartnerType.RESELLER).and().tenant("btcl_res_5", "btcl").partner(501, "Res5 client", PartnerType.CUSTOMER).and().build());
    private RoutesphereKit kit;

    @AfterEach
    void close() {
        if (kit != null) kit.bootstrapRoad().orElseThrow().close();
        lab.close();
    }

    /** One root's trees that pass the road's gate on a reload and hand the doorbell's words to whoever listens. */
    static final class WordTrees implements LiveTrees {
        private final Tenant tree;
        private volatile TreeGate gate = TreeGate.OPEN;
        private volatile BiConsumer<String, String> words = (root, message) -> { };
        WordTrees(Tenant tree) { this.tree = tree; }
        void ring(String root, String message) { words.accept(root, message); }
        @Override public Set<String> roots() { return Set.of("btcl"); }
        @Override public void serve(String root) { }
        @Override public void reload(String root) { gate.pass(root, tree); }
        @Override public void gateEachNewTree(TreeGate gate) { this.gate = gate; }
        @Override public void onDoorbellWords(BiConsumer<String, String> words) { this.words = words; }
        private TenantLookup lookup() { return TenantLookup.of(tree); }
        @Override public Optional<Tenant> root(String db) { return lookup().root(db); }
        @Override public Optional<Tenant> tenantOfPartner(String db, int id) { return lookup().tenantOfPartner(db, id); }
        @Override public Optional<Tenant> tenantByDbName(String db, String name) { return lookup().tenantByDbName(db, name); }
        @Override public Optional<Tenant> rootOfCode(String code) { return lookup().rootOfCode(code); }
        @Override public Optional<RootTree> snapshot(String root) { return lookup().snapshot(root); }
    }

    @Test
    void anInsertsWords_makeTheNewAccountPay_aTreeReloadAndItsOwnTablesUpdate_reReadNothing() {
        kitOnTheLab();
        OwnedMemLedgerPort btcl = (OwnedMemLedgerPort) ((PerRootLedger) kit.ledger()).of("btcl").orElseThrow();
        lab.account("btcl_res_5", 77, 70, "BDT", "50.00");                                    // a purchase, behind the ledger
        assertThatThrownBy(() -> kit.ledger().reserve(levelOf(77), new BigDecimal("1.00"), "w-1#L0")).isInstanceOf(LedgerPort.LedgerRefusal.class);

        trees.reload("btcl");                                                                 // a tree's reload alone
        trees.ring("btcl", "{\"what\":\"change\",\"changes\":[\"btcl.packageaccountreserve:UPDATE\"]}");
        assertThat(btcl.mergesRun()).as("nothing re-read").isZero();

        trees.ring("btcl", "{\"what\":\"change\",\"changes\":[\"btcl_res_5.packageaccount:INSERT\"]}");

        assertThat(btcl.mergesRun()).isEqualTo(1);
        assertThat(kit.ledger().reserve(levelOf(77), new BigDecimal("1.00"), "w-2#L0")).as("it pays").isPresent();
    }

    private void kitOnTheLab() {
        Map<String, Object> ledger = Map.of("kind", "switch", "entities", "packageaccount, packageaccountreserve", "cache-only", "packageaccountreserve");
        Map<String, Object> envelope = Map.of("app", Map.of("code", "wifi-sphere"), "tenant", Map.of("code", "btcl"), "config", Map.of("routesphere", Map.of(
            "roots", List.of("btcl"), "pool", 4, "threads", 1,
            "identification", Map.of("rules", List.of(Map.of("root", "btcl", "kind", "vlan", "match", "gw-1:310"))),
            "root", Map.of("btcl", Map.of(
                "switch-db", Map.of("url", lab.url(), "schema", "btcl", "username", "sa"),
                "kafka", Map.of("bootstrap", "kafka.test:9092"),
                "ledger", ledger,
                "journal", Map.of("dir", dir.toString()))))));
        KitMakers makers = KitMakers.builder().trees((d, src) -> trees).ledger(new SwitchLedgerMaker()).kafka(new FakeKafka())
            .journals(root -> SessionJournals.none()).environment(name -> null).build();
        kit = RoutesphereKit.fromBootstrap(MapBootstrapSource.of(envelope), "wifi-sphere", makers);
    }

    private LevelAdmission levelOf(long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(501);
        PackageAccount account = new PackageAccount();
        account.setId(accountId);
        account.setUom("BDT");
        LevelAdmission level = new LevelAdmission(0, kit.tenants().tenantByDbName("btcl", "btcl_res_5").orElseThrow(), partner, account);
        level.setRate(new BigDecimal("1.00"));
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }
}
