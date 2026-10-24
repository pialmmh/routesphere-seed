package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-L item 3 · a {@code password-ref} that is not {@code env:<NAME>} may be a password written by mistake: no record prints it (P1-K
 * stopped repeating it in the refusals; this finishes the job). {@code SwitchDb} — and so {@code RootResources} and {@code Deployment} —
 * and the ledger's own words (its {@code db} block) print it as {@value RootResources#HIDDEN_REF}; an {@code env:<NAME>} reference is
 * printed as it is (the variable's name is what an operator needs). The written values here are generated.
 */
class NoWrittenPasswordRefIsPrintedTest {

    private final String written = "pw-" + UUID.randomUUID(), writtenForTheLedger = "pw-" + UUID.randomUUID();

    @Test
    void aSwitchDb_printsAnEnvReference_butHidesAWrittenOne() {
        assertThat(new RootResources.SwitchDb("jdbc:h2:mem:x", "btcl", "ad_sphere", "env:AD_SWITCH_DB_PASSWORD").toString()).contains("env:AD_SWITCH_DB_PASSWORD");
        assertThat(new RootResources.SwitchDb("jdbc:h2:mem:x", "btcl", "ad_sphere", written).toString())
            .doesNotContain(written).contains(RootResources.HIDDEN_REF).contains("ad_sphere");
    }

    @Test
    void aRootsResources_hideAWrittenRef_inBothLogins() {
        RootResources btcl = new RootResources("btcl", new RootResources.SwitchDb("jdbc:h2:mem:x", "btcl", "ad_sphere", written),
            new RootResources.KafkaRoad("10.10.188.2:9092", 1, (short) 1), null, new RootResources.Ledger(RootResources.Ledger.SWITCH, Map.of()), Path.of("/tmp/x"),
            new RootResources.SwitchDb(null, null, "btcl_ledger", writtenForTheLedger));

        assertThat(btcl.toString()).doesNotContain(written).doesNotContain(writtenForTheLedger);
    }

    @Test
    void aDeploymentRead_withWrittenRefs_inSwitchDbAndInTheLedgersOwnLogin_printsNeither() {
        Deployment d = Deployment.of(theLoginSampleWith(written, writtenForTheLedger), "ad-sphere");

        assertThat(d.toString()).doesNotContain(written).doesNotContain(writtenForTheLedger).contains(RootResources.HIDDEN_REF);
        assertThat(d.root("btcl").orElseThrow().ledger().toString()).as("the ledger's own words: its db block").doesNotContain(writtenForTheLedger);
        assertThat(Deployment.of(MapBootstrapSource.read("/bootstrap/deployment-with-ledger-login.json"), "ad-sphere").toString())
            .as("env: references are printed as they are").contains("env:BTCL_LEDGER_DB_PASSWORD");
    }

    /** The P1-J sample (btcl with its ledger's own login), btcl's switch-db and ledger.db password-refs written as values. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> theLoginSampleWith(String switchDbRef, String ledgerDbRef) {
        Map<String, Object> envelope = new HashMap<>(MapBootstrapSource.read("/bootstrap/deployment-with-ledger-login.json"));
        Map<String, Object> config = new HashMap<>((Map<String, Object>) envelope.get("config"));
        Map<String, Object> rs = new HashMap<>((Map<String, Object>) config.get("routesphere"));
        Map<String, Object> roots = new HashMap<>((Map<String, Object>) rs.get("root"));
        Map<String, Object> btcl = new HashMap<>((Map<String, Object>) roots.get("btcl"));
        Map<String, Object> switchDb = new HashMap<>((Map<String, Object>) btcl.get("switch-db"));
        switchDb.put("password-ref", switchDbRef);
        btcl.put("switch-db", switchDb);
        btcl.put("ledger", Map.of("kind", "switch", "db", Map.of("user", "btcl_ledger", "password-ref", ledgerDbRef)));
        roots.put("btcl", btcl);
        rs.put("root", roots);
        config.put("routesphere", rs);
        envelope.put("config", config);
        return envelope;
    }
}
