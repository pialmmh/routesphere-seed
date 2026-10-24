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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-J item 1 · accounts written AFTER their tier exists reach the switch ledger (ad-sphere P2c's G1, run 8): prime-context's road makes
 * the tier (the event), THEN the BSS writes its accounts (a purchase), prime-context's tree is rebuilt and the doorbell rings — the tree
 * shows the account, and before this round the ledger answered ACCOUNT_NOT_FOUND until another tier came or the process restarted. Every
 * reload of a root's tree now merges the root's new rows into its switch ledger — coalesced: at most one merge per spacing, the first ring
 * at once, the rest of a burst ONE merge at the end of the spacing. MemLedger on H2 in memory, as the lab's.
 */
class NewAccountsReachTheLedgerTest {

    @TempDir Path dir;
    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("btcl_res_5").account("btcl", 1, 10, "BDT", "100.00");
    private final GatedTrees trees = new GatedTrees(btcl());
    private RoutesphereKit kit;

    @AfterEach
    void close() throws Exception {
        if (kit != null) kit.bootstrapRoad().orElseThrow().close();
        lab.close();
    }

    /** One root's trees whose every reload passes the road's gate before it is shown — the doorbell's ring as seed-tenant runs it. */
    static final class GatedTrees implements LiveTrees {
        private final Tenant tree;
        private volatile TreeGate gate = TreeGate.OPEN;
        GatedTrees(Tenant tree) { this.tree = tree; }
        @Override public Set<String> roots() { return Set.of("btcl"); }
        @Override public void serve(String root) { }
        @Override public void reload(String root) { gate.pass(root, tree); }
        @Override public void gateEachNewTree(TreeGate gate) { this.gate = gate; }
        private TenantLookup lookup() { return TenantLookup.of(tree); }
        @Override public Optional<Tenant> root(String db) { return lookup().root(db); }
        @Override public Optional<Tenant> tenantOfPartner(String db, int id) { return lookup().tenantOfPartner(db, id); }
        @Override public Optional<Tenant> tenantByDbName(String db, String name) { return lookup().tenantByDbName(db, name); }
        @Override public Optional<Tenant> rootOfCode(String code) { return lookup().rootOfCode(code); }
        @Override public Optional<RootTree> snapshot(String root) { return lookup().snapshot(root); }
    }

    private static Tenant btcl() {
        return new TenantTreeBuilder().root("btcl").partner(1, "client", PartnerType.CUSTOMER).partner(5, "Res5", PartnerType.RESELLER).and()
            .tenant("btcl_res_5", "btcl").partner(501, "Res5 client", PartnerType.CUSTOMER).and().build();
    }

    private RoutesphereKit kitOnTheLab(Map<String, Object> ledgerWords) {
        Map<String, Object> ledger = new HashMap<>(Map.of("kind", "switch"));
        ledger.putAll(ledgerWords);
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
        return kit;
    }

    /** The tier's own partner and its account, as the rating step names them — the tier as the kit's tree holds it (its chain names its root). */
    private LevelAdmission levelOf(String tierDb, long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(501);
        partner.setPartnerName("Res5 client");
        PackageAccount account = new PackageAccount();
        account.setId(accountId);
        account.setUom("BDT");
        Tenant tier = kit.tenants().tenantByDbName("btcl", tierDb).orElseThrow();
        LevelAdmission level = new LevelAdmission(0, tier, partner, account);
        level.setRate(new BigDecimal("1.00"));
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    @Test
    void anAccountWrittenAfterItsTierExists_reachesTheLedgerOnTheNextRing() {
        RoutesphereKit kit = kitOnTheLab(Map.of());
        lab.account("btcl_res_5", 77, 70, "BDT", "50.00");                                   // the BSS's purchase, after the tier exists
        assertThatThrownBy(() -> kit.ledger().reserve(levelOf("btcl_res_5", 77), new BigDecimal("1.00"), "s-1#L0"))
            .isInstanceOf(LedgerPort.LedgerRefusal.class).hasMessageContaining("not held");    // P2c run 8: the tree has it, the ledger not yet

        trees.reload("btcl");                                                                // the doorbell rings

        assertThat(kit.ledger().reserve(levelOf("btcl_res_5", 77), new BigDecimal("1.00"), "s-2#L0")).as("it pays").isPresent();
    }

    @Test
    void aBurstOfRings_isOneMergeAtOnce_andOneAtTheEndOfTheSpacing_anAccountWrittenInTheBurstReachesTheLedger() {
        RoutesphereKit kit = kitOnTheLab(Map.of("merge-spacing-sec", 3));                  // the whole burst fits in the spacing, loaded or not
        OwnedMemLedgerPort btcl = (OwnedMemLedgerPort) ((PerRootLedger) kit.ledger()).of("btcl").orElseThrow();

        trees.reload("btcl");                                                                // the first ring: merged at once
        lab.account("btcl_res_5", 78, 70, "BDT", "50.00");                                   // a purchase in the burst
        for (int i = 0; i < 5; i++) trees.reload("btcl");                                    // the rest of the burst

        assertThat(btcl.mergesRun()).as("one merge at once; the burst waits for the end of the spacing").isEqualTo(1);
        await(() -> btcl.mergesRun() == 2);
        assertThat(kit.ledger().reserve(levelOf("btcl_res_5", 78), new BigDecimal("1.00"), "s-3#L0")).as("the burst's account, merged").isPresent();
        assertThat(btcl.mergesRun()).as("ONE merge for the rest of the burst").isEqualTo(2);
    }

    private static void await(BooleanSupplier condition) {
        long until = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        assertThat(condition.getAsBoolean()).as("within 30 s").isTrue();
    }
}
