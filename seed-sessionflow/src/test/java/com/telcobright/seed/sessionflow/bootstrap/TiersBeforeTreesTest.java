package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.api.TenantCreated;
import com.telcobright.seed.sessionflow.api.TierSettlement;
import com.telcobright.seed.sessionflow.base.LabCall;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabFlow;
import com.telcobright.seed.sessionflow.base.LabSupervisor;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-G item 2 · a root's new tree never shows a tier its ledger lacks (P2 gap 10). A reseller's provisioning rings the doorbell
 * {@code config_event_loader_<root>} AND sends {@code tenant_created} — and the ring can come first. Whatever reloads the tree, a tier its
 * root's ledger was not built over is handed to that ledger FIRST, once: the event and the ring together are one hand.
 */
class TiersBeforeTreesTest {

    /** A root's ledger with books per tier, as the switch ledger keeps them: built over its tree's tiers, each hand and load written down. */
    static final class TierBooks implements LedgerPort {
        final Set<String> tiers = ConcurrentHashMap.newKeySet();
        final List<String> hands = new CopyOnWriteArrayList<>();
        final List<String> loads = new CopyOnWriteArrayList<>();
        final AtomicInteger refusals = new AtomicInteger();
        volatile TenantLookup shown = TenantLookup.of();
        final InMemoryLedger money = new InMemoryLedger();

        @Override
        public void tierAdded(String tierDb) {
            hands.add(tierDb);
            if (refusals.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new IllegalStateException("the tier " + tierDb + " cannot be loaded yet (scripted)");
            if (!tiers.add(tierDb)) return;
            loads.add(tierDb + (shown.tenantByDbName("btcl", tierDb).isPresent() ? " AFTER the tree showed it" : " before the tree showed it"));
        }

        @Override public Optional<Reservation> reserve(LevelAdmission level, BigDecimal amount, String reference) { return money.reserve(level, amount, reference); }
        @Override public TierSettlement settle(LevelAdmission level, BigDecimal charged) { return money.settle(level, charged); }
        @Override public void release(LevelAdmission level, String why) { money.release(level, why); }
    }

    private final BootstrapWorld w = new BootstrapWorld();
    private final Map<String, TierBooks> books = new ConcurrentHashMap<>();
    private RoutesphereEngine<LabContext> engine;
    private RoutesphereKit kit;

    @AfterEach
    void stop() { if (engine != null) engine.close(); }

    /** btcl after a reseller's provisioning: its tier btcl_res_9 and the NEW btcl_res_11. */
    static Tenant btclWithANewTier() {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root("btcl").partner(1, "btcl-client", PartnerType.CUSTOMER).partner(9, "btcl-res", PartnerType.RESELLER).partner(11, "btcl-res-11", PartnerType.RESELLER);
        b.tenant("btcl_res_9", "btcl").partner(501, "btcl-res-client", PartnerType.CUSTOMER);
        b.tenant("btcl_res_11", "btcl").partner(601, "btcl-res-11-client", PartnerType.CUSTOMER);
        return b.build();
    }

    private void up() {
        w.source.serve(BootstrapWorld.small(MapBootstrapSource.read("/bootstrap/deployment.json")));
        kit = RoutesphereKit.fromBootstrap(w.source, "ad-sphere", w.makers(new LedgerMaker() {
            @Override public String kind() { return RootResources.Ledger.SWITCH; }
            @Override public LedgerPort make(RootResources root, Context context) { return booksBuiltOver(root.root(), context.trees().snapshot(root.root()).orElseThrow()); }
        }));
        books.values().forEach(b -> b.shown = kit.tenants());
        engine = RoutesphereEngine.of(new LabSupervisor(new LabFlow(kit, Map.of(), List.of(), Set.of()))).signaling(LabCall.spec())
            .channel(new TestChannel<String, StatemachineEvent>("lab"), (id, frame) -> Delivery.to(LabCall.TYPE, frame)).start();
    }

    private TierBooks booksBuiltOver(String root, RootTree tree) {
        TierBooks b = new TierBooks();
        b.tiers.add(tree.rootDbName());
        tree.tree().getTenantIndex().values().forEach(t -> b.tiers.add(t.getDbName()));
        books.put(root, b);
        return b;
    }

    private static TenantCreated tierCreated() {
        return new TenantCreated("btcl", "btcl_res_11", "btcl", 11, "reseller", List.of("ad-sphere"), "2026-10-24T00:40:00Z");
    }

    @Test
    void aDoorbellReloadBeforeTheEvent_handsTheNewTierToItsLedgerFirst() {
        up();
        w.trees.prime.put("btcl", btclWithANewTier());                                   // prime-context's tree after the provisioning

        w.trees.reload("btcl");                                                           // the ring first: the trees reload on their own

        assertThat(books.get("btcl").loads).containsExactly("btcl_res_11 before the tree showed it");
        assertThat(kit.tenants().tenantByDbName("btcl", "btcl_res_11")).as("then shown").isPresent();
        assertThat(books.get("link3").hands).as("never another root's ledger").isEmpty();
    }

    @Test
    void theRingAndTheEventTogether_handTheTierOnce() {
        up();
        w.trees.prime.put("btcl", btclWithANewTier());

        w.trees.reload("btcl");
        kit.bootstrapRoad().orElseThrow().onTenantCreated(tierCreated());

        assertThat(books.get("btcl").hands).as("one hand for the ring and the event").containsExactly("btcl_res_11");
        assertThat(books.get("btcl").loads).hasSize(1);
    }

    @Test
    void aReloadWithNoNewTier_handsNothing_theTiersItsLedgerWasBuiltOverAreItsOwn() {
        up();
        w.trees.reload("btcl");
        assertThat(books.get("btcl").hands).isEmpty();
    }

    @Test
    void aLedgerThatCannotTakeTheTierYet_keepsTheNewTreeFromBeingShown_andTheNextReloadShowsIt() {
        up();
        books.get("btcl").refusals.set(1);
        w.trees.prime.put("btcl", btclWithANewTier());

        assertThatThrownBy(() -> w.trees.reload("btcl")).hasMessageContaining("btcl_res_11");
        assertThat(kit.tenants().tenantByDbName("btcl", "btcl_res_11")).as("the last tree stays").isEmpty();

        w.trees.reload("btcl");
        assertThat(kit.tenants().tenantByDbName("btcl", "btcl_res_11")).isPresent();
        assertThat(books.get("btcl").loads).containsExactly("btcl_res_11 before the tree showed it");
    }
}
