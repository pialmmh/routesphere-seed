package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RootTree;
import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.api.RoutesphereSupervisor;
import com.telcobright.seed.sessionflow.dependencies.KitMakers;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.dependencies.SessionJournals;
import com.telcobright.seed.sessionflow.spi.LiveTrees;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.FakeKafka;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerMaker;
import com.telcobright.seed.switchledger.samples.LedgerFlow;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import com.telcobright.statewalk.registry.MachineSpec;
import com.telcobright.statewalk.session.events.Connect;
import com.telcobright.statewalk.session.events.Reject;
import com.telcobright.statewalk.session.events.SessionStart;
import com.telcobright.statewalk.session.events.SignalingFailed;
import com.telcobright.statewalk.session.events.Stop;
import com.telcobright.statewalk.state.StateMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-E items 6 and 13 · the switch ledger on a kit made from the bootstrap, end to end: the kit's {@code switch} root runs a REAL MemLedger
 * (H2 in memory, the lab's), and a closed engine leaves no MemLedger thread behind — the road closes the ledgers it made.
 */
class SwitchLedgerOnTheBootstrapKitTest {

    @TempDir Path dir;
    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("btcl_res_5");

    @AfterEach
    void close() throws Exception { lab.close(); }

    /** The smallest signaling machine a session needs: the login starts it; every live state takes Reject and Stop. */
    record Login(LedgerFlow.Facts facts) implements StatemachineEvent {
        @Override public boolean isFirst() { return true; }
    }

    static MachineSpec<Object> gate() {
        return MachineSpec.builder().name("Gate").contextFactory(Object::new)
            .upSample(new SessionStart(new LedgerFlow.Facts("sample", 0, LedgerFlow.GATEWAY, "02:00:00:00:00:01")))
            .stateMap(StateMap.builder().initialState(StateMap.IDLE)
                .state(StateMap.IDLE).timeout(0).on(Login.class, "INIT", null, null, (self, e) -> new SessionStart(((Login) e).facts()))
                .state("INIT").interim().timeout(0)
                    .on(Connect.class, "DONE", null, null, (self, e) -> new SignalingFailed(System.currentTimeMillis(), "NO_GATE", 0))
                    .on(Reject.class, "DONE", null, null, (self, e) -> new SignalingFailed(System.currentTimeMillis(), ((Reject) e).cause(), 0))
                    .on(Stop.class, "DONE", null, null, (self, e) -> new SignalingFailed(System.currentTimeMillis(), ((Stop) e).cause(), 0))
                .state("DONE").finalState().timeout(1, SECONDS, "DONE")
                .build())
            .build();
    }

    private Map<String, Object> envelope() {
        return Map.of("app", Map.of("code", "wifi-sphere"), "tenant", Map.of("code", "platform"), "config", Map.of("routesphere", Map.of(
            "roots", List.of("btcl"), "pool", 4, "threads", 1,
            "identification", Map.of("rules", List.of(Map.of("root", "btcl", "kind", "vlan", "match", "gw-1:310"))),
            "root", Map.of("btcl", Map.of(
                "switch-db", Map.of("url", lab.url(), "schema", "btcl", "username", "sa"),
                "kafka", Map.of("bootstrap", "kafka.test:9092"),
                "ledger", Map.of("kind", "switch"),
                "journal", Map.of("dir", dir.toString()))))));
    }

    private static Tenant btcl() {
        return new TenantTreeBuilder().root("btcl").partner(1, "client", PartnerType.CUSTOMER).partner(5, "Res5", PartnerType.RESELLER).and()
            .tenant("btcl_res_5", "btcl").partner(501, "Res5 client", PartnerType.CUSTOMER).and().build();
    }

    /** The trees of a test: one root, served from memory. */
    static LiveTrees served(TenantLookup lookup, String root) {
        return new LiveTrees() {
            @Override public Set<String> roots() { return Set.of(root); }
            @Override public void serve(String r) { }
            @Override public void reload(String r) { }
            @Override public Optional<Tenant> root(String db) { return lookup.root(db); }
            @Override public Optional<Tenant> tenantOfPartner(String db, int id) { return lookup.tenantOfPartner(db, id); }
            @Override public Optional<Tenant> tenantByDbName(String db, String name) { return lookup.tenantByDbName(db, name); }
            @Override public Optional<Tenant> rootOfCode(String code) { return lookup.rootOfCode(code); }
            @Override public Optional<RootTree> snapshot(String r) { return lookup.snapshot(r); }
        };
    }

    @Test
    void aClosedEngine_leavesNoMemLedgerThreadBehind() throws Exception {
        long before = walConsumers();
        KitMakers makers = KitMakers.builder().trees((d, src) -> served(TenantLookup.of(btcl()), "btcl")).ledger(new SwitchLedgerMaker())
            .kafka(new FakeKafka()).journals(root -> SessionJournals.none()).environment(name -> null).build();
        RoutesphereKit kit = RoutesphereKit.fromBootstrap(MapBootstrapSource.of(envelope()), "wifi-sphere", makers);
        LedgerFlow flow = new LedgerFlow(kit, Map.of(), Map.of());
        RoutesphereEngine<LedgerFlow.Session> engine = RoutesphereEngine.of(new RoutesphereSupervisor<LedgerFlow.Session>(flow, LedgerFlow.Session::new) { })
            .signaling(gate()).channel(new TestChannel<String, StatemachineEvent>("gate"), (id, frame) -> Delivery.to("Gate", frame)).start();
        assertThat(walConsumers()).as("the root's switch ledger runs its MemLedger").isEqualTo(before + 1);

        engine.close();

        long until = System.currentTimeMillis() + 5_000;
        while (walConsumers() > before && System.currentTimeMillis() < until) Thread.sleep(10);
        assertThat(walConsumers()).as("the engine's close shut the MemLedger down").isEqualTo(before);
        assertThat(kit.resolver().tenantOf(Ingress.vlan("gw-1", 310))).contains("btcl");
    }

    private static long walConsumers() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && "MemLedger-WAL-Consumer".equals(t.getName())).count();
    }
}
