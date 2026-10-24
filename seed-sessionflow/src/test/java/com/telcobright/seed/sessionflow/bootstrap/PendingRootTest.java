package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.seed.sessionflow.api.RoutesphereEngine;
import com.telcobright.seed.sessionflow.api.TenantCreated;
import com.telcobright.seed.sessionflow.base.LabCall;
import com.telcobright.seed.sessionflow.base.LabContext;
import com.telcobright.seed.sessionflow.base.LabFlow;
import com.telcobright.seed.sessionflow.base.LabSupervisor;
import com.telcobright.seed.sessionflow.dependencies.KitMakers;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.internal.BootstrapRoad;
import com.telcobright.seed.sessionflow.testkit.ManualClock;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.channel.TestChannel;
import com.telcobright.statewalk.event.StatemachineEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-G item 1 · prime-context sends a {@code kind: root} {@code tenant_created} at the root's provisioning, BEFORE it serves the root
 * (PC-0022 §9.3, §10 #1): the root's tree road answers 404 ROOT_NOT_SERVED until tenant-master's switch row exists, and the deployment
 * document may list the root only later. Such a root is never half-served: it goes PENDING — said once, with the reason — and is tried
 * again every {@code pending-root.retry-sec} (the bootstrap read again, the tree loaded) until it is served (said once) or given up after
 * {@code pending-root.give-up-min} (said once). The engine's close stops the tries.
 */
class PendingRootTest {

    private final BootstrapWorld w = new BootstrapWorld();
    private final Map<String, Object> sample = retryEverySecond(BootstrapWorld.small(MapBootstrapSource.read("/bootstrap/deployment.json")));
    private RoutesphereEngine<LabContext> engine;
    private RoutesphereKit kit;

    @AfterEach
    void stop() { if (engine != null) engine.close(); }

    /** The envelope with config.routesphere.pending-root.retry-sec = 1 (the default is 30). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> retryEverySecond(Map<String, Object> envelope) {
        Map<String, Object> copy = new HashMap<>(envelope);
        Map<String, Object> config = new HashMap<>((Map<String, Object>) envelope.get("config"));
        Map<String, Object> rs = new HashMap<>((Map<String, Object>) config.get("routesphere"));
        rs.put("pending-root", Map.of("retry-sec", 1));
        config.put("routesphere", rs);
        copy.put("config", config);
        return copy;
    }

    private void upOnBtclAlone(KitMakers makers) {
        w.source.serve(BootstrapWorld.servingOnly(sample, "btcl"));
        kit = RoutesphereKit.fromBootstrap(w.source, "ad-sphere", makers);
        engine = RoutesphereEngine.of(new LabSupervisor(new LabFlow(kit, Map.of(), List.of(), Set.of()))).signaling(LabCall.spec())
            .channel(new TestChannel<String, StatemachineEvent>("lab"), (id, frame) -> Delivery.to(LabCall.TYPE, frame)).start();
    }

    private BootstrapRoad road() { return kit.bootstrapRoad().orElseThrow(); }

    private static TenantCreated rootCreated(String root) { return new TenantCreated(root, root, null, null, "root", List.of("ad-sphere"), "2026-10-24T00:30:00Z"); }

    private List<String> served() { return kit.deployment().orElseThrow().roots(); }

    @Test
    void aRootWhoseTreeIsNotServedYet_goesPending_andTheRetryServesIt_withNoNewWord() {
        upOnBtclAlone(w.makers());
        w.trees.prime.remove("link3");                                                     // prime-context answers 404 ROOT_NOT_SERVED
        w.source.serve(sample);                                                            // the document lists link3 already

        road().onTenantCreated(rootCreated("link3"));

        assertThat(served()).as("not half-served").containsExactly("btcl");
        assertThat(w.trees.roots()).containsExactly("btcl");
        assertThat(road().pendingRoots()).containsExactly("link3");
        w.trees.prime.put("link3", BootstrapWorld.root("link3", 5));                       // tenant-master's row, prime-context's next build
        await(() -> served().contains("link3"));

        assertThat(served()).containsExactly("btcl", "link3");
        assertThat(road().pendingRoots()).isEmpty();
        assertThat(w.ledgerAsks).extracting(r -> r.root()).containsExactly("btcl", "link3");
        assertThat(road().said()).filteredOn(line -> line.contains("link3")).hasSize(2)
            .anySatisfy(line -> assertThat(line).contains("not served yet").contains("prime-context does not serve its tree yet"))
            .anySatisfy(line -> assertThat(line).contains("served from now on"));
    }

    @Test
    void aRootNotListedYet_goesPending_andIsServedOnceTheDocumentListsIt() {
        upOnBtclAlone(w.makers());

        road().onTenantCreated(rootCreated("link3"));                                      // the document still serves btcl alone
        road().onTenantCreated(rootCreated("link3"));                                      // the same word again: tried, still pending

        assertThat(served()).containsExactly("btcl");
        assertThat(road().pendingRoots()).containsExactly("link3");
        assertThat(road().said()).singleElement().asString().contains("link3").contains("not listed for ad-sphere yet");
        w.source.serve(sample);                                                            // prime-context's document PUT lists it
        await(() -> served().contains("link3"));
        assertThat(road().pendingRoots()).isEmpty();
    }

    @Test
    void aPendingRoot_isGivenUpAfterItsTime_saidOnce_andANewWordServesIt() {
        ManualClock clock = new ManualClock(Instant.parse("2026-10-24T00:00:00Z"), ZoneId.of("UTC"));
        upOnBtclAlone(w.makers(clock));
        road().onTenantCreated(rootCreated("link3"));
        assertThat(road().pendingRoots()).containsExactly("link3");

        clock.advance(Duration.ofMinutes(121).toMillis());                                        // the default give-up: 120 min
        await(() -> road().pendingRoots().isEmpty());

        assertThat(road().said()).filteredOn(line -> line.contains("was given up")).singleElement().asString()
            .contains("link3").contains("a restart, or a new tenant_created, serves it");
        assertThat(served()).containsExactly("btcl");
        w.source.serve(sample);
        road().onTenantCreated(rootCreated("link3"));                                      // a new word
        assertThat(served()).containsExactly("btcl", "link3");
    }

    @Test
    void theEnginesClose_stopsTheTries() {
        upOnBtclAlone(w.makers());
        road().onTenantCreated(rootCreated("link3"));
        await(() -> threadNamed("ad-sphere-pending-roots"));

        engine.close();
        engine = null;

        await(() -> !threadNamed("ad-sphere-pending-roots"));
        assertThat(road().pendingRoots()).isEmpty();
    }

    private static boolean threadNamed(String name) { return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && t.getName().equals(name)); }

    private static void await(BooleanSupplier condition) {
        long until = System.currentTimeMillis() + 8_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        assertThat(condition.getAsBoolean()).as("within 8 s").isTrue();
    }
}
