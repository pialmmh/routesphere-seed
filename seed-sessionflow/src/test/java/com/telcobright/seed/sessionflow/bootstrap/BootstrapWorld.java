package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.dependencies.KitMakers;
import com.telcobright.seed.sessionflow.dependencies.SessionJournals;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.testkit.FakeKafka;
import com.telcobright.seed.sessionflow.testkit.InMemoryLedger;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The world of the bootstrap tests: prime-context's §C sample ({@code /bootstrap/deployment.json}: btcl and link3 on the bed), the two roots'
 * trees, and makers with no network — the trees in memory, an in-memory ledger per root for the {@code switch} kind, the fake Kafka, a
 * journal per root's directory written down and never opened (the sample's directories are the bed's, not this box's).
 */
final class BootstrapWorld {

    final MapBootstrapSource source = MapBootstrapSource.ofResource("/bootstrap/deployment.json");
    final FakeKafka kafka = new FakeKafka();
    final Map<String, Tenant> prime = new ConcurrentHashMap<>(Map.of("btcl", root("btcl", 9), "link3", root("link3", 5)));
    final Map<String, InMemoryLedger> ledgers = new ConcurrentHashMap<>();
    final List<RootResources> ledgerAsks = new CopyOnWriteArrayList<>();
    final List<Path> journalDirs = new CopyOnWriteArrayList<>();
    FakeLiveTrees trees;

    /** A root with one client (partner 1) and one reseller tier ({@code <root>_res_<reseller>}, its client 501). */
    static Tenant root(String name, int reseller) {
        TenantTreeBuilder b = new TenantTreeBuilder();
        b.root(name).partner(1, name + "-client", PartnerType.CUSTOMER).partner(reseller, name + "-res", PartnerType.RESELLER);
        b.tenant(name + "_res_" + reseller, name).partner(501, name + "-res-client", PartnerType.CUSTOMER);
        return b.build();
    }

    /** The sample with a pool of 8 and 2 threads: an engine a test starts on it builds its pools at once. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> small(Map<String, Object> envelope) {
        Map<String, Object> copy = new java.util.HashMap<>(envelope);
        Map<String, Object> config = new java.util.HashMap<>((Map<String, Object>) envelope.get("config"));
        Map<String, Object> rs = new java.util.HashMap<>((Map<String, Object>) config.get("routesphere"));
        rs.put("pool", 8);
        rs.put("threads", 2);
        config.put("routesphere", rs);
        copy.put("config", config);
        return copy;
    }

    /** The envelope serving one root alone: its roots, its rules, its block (prime-context before it provisioned the others for this app). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> servingOnly(Map<String, Object> envelope, String root) {
        Map<String, Object> copy = new java.util.HashMap<>(envelope);
        Map<String, Object> config = new java.util.HashMap<>((Map<String, Object>) envelope.get("config"));
        Map<String, Object> rs = new java.util.HashMap<>((Map<String, Object>) config.get("routesphere"));
        rs.put("roots", List.of(root));
        List<Object> rules = (List<Object>) ((Map<String, Object>) rs.get("identification")).get("rules");
        rs.put("identification", Map.of("rules", rules.stream().filter(r -> root.equals(((Map<String, Object>) r).get("root"))).toList()));
        rs.put("root", Map.of(root, ((Map<String, Object>) rs.get("root")).get(root)));
        config.put("routesphere", rs);
        copy.put("config", config);
        return copy;
    }

    KitMakers makers() {
        return makers(new LedgerMaker() {
            @Override public String kind() { return RootResources.Ledger.SWITCH; }
            @Override public LedgerPort make(RootResources root, Context context) {
                ledgerAsks.add(root);
                return ledgers.computeIfAbsent(root.root(), r -> new InMemoryLedger());
            }
        });
    }

    /** The same makers with a switch ledger of the test's own. */
    KitMakers makers(LedgerMaker switchLedger) {
        return KitMakers.builder()
            .trees((deployment, src) -> trees = new FakeLiveTrees(prime, deployment.roots()))
            .ledger(switchLedger)
            .kafka(kafka)
            .journals(dir -> { journalDirs.add(dir); return SessionJournals.none(); })
            .environment(name -> null)
            .build();
    }
}
