package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.dependencies.LedgerSettings;
import com.telcobright.seed.sessionflow.dependencies.Ledgers;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereKit;
import com.telcobright.seed.sessionflow.internal.BootstrapRoad;
import com.telcobright.seed.sessionflow.internal.HttpBootstrapSource;
import com.telcobright.seed.sessionflow.spi.BootstrapSource;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-M item 2 · a password inside a url is never printed — the start line ({@code BootstrapRoad}'s: every root's switch-db and redis url,
 * the bootstrap's own), the records' text ({@code SwitchDb}, {@code RootResources}, the ledger's words, {@code LedgerSettings},
 * {@code Deployment}), the orchestrix ledger's lines: ONE helper, {@code LogText.maskPassword}. The url used to CONNECT is the one written.
 * The secrets here are generated.
 */
class NoPasswordInAUrlIsPrintedTest {

    @TempDir Path dir;
    private final String secret = "pw-" + UUID.randomUUID(), other = "pw-" + UUID.randomUUID();

    @Test
    void theStartLine_masksEveryRootsRedisAndSwitchDbPassword_theRootConnectsWithTheUrlAsWritten() {
        BootstrapWorld w = new BootstrapWorld();
        w.source.serve(theSampleWith("redis://:" + secret + "@10.10.188.2:6379", "jdbc:postgresql://10.10.188.2:5434/routesphere?password=" + other));

        RoutesphereKit[] kit = new RoutesphereKit[1];
        String log = theLogOf(() -> kit[0] = RoutesphereKit.fromBootstrap(w.source, "ad-sphere", w.makers()));
        try {
            assertThat(log).contains("the bootstrap of").doesNotContain(secret).doesNotContain(other)
                .contains("redis redis://:***@10.10.188.2:6379").contains("on jdbc:postgresql://10.10.188.2:5434/routesphere?password=***");
            RootResources btcl = kit[0].bootstrapRoad().orElseThrow().deployment().root("btcl").orElseThrow();
            assertThat(btcl.redisUrl()).as("the url used to connect is the one written").isEqualTo("redis://:" + secret + "@10.10.188.2:6379");
            assertThat(btcl.switchDb().url()).endsWith("?password=" + other);
        } finally {
            kit[0].bootstrapRoad().ifPresent(BootstrapRoad::close);
        }
    }

    @Test
    void theRecords_maskAPasswordInsideAUrl() {
        RootResources.SwitchDb db = new RootResources.SwitchDb("jdbc:mysql://ad:" + secret + "@10.10.1.1:3306/routesphere", "btcl", "ad", "env:X");
        RootResources btcl = new RootResources("btcl", db, new RootResources.KafkaRoad("10.10.188.2:9092", 1, (short) 1), "redis://ad:" + other + "@h:6379",
            new RootResources.Ledger(RootResources.Ledger.ORCHESTRIX, Map.of("url", "https://portal:" + secret + "@10.10.1.2/api")), dir);
        LedgerSettings orchestrix = LedgerSettings.of("https://portal:" + secret + "@10.10.1.2/api", "PORTAL_API_TOKEN", "btcl");

        assertThat(db.toString()).doesNotContain(secret).contains("ad:***@10.10.1.1");
        assertThat(btcl.toString()).doesNotContain(secret).doesNotContain(other).contains("redis://ad:***@h:6379");
        assertThat(btcl.ledger().toString()).doesNotContain(secret);
        assertThat(orchestrix.toString()).doesNotContain(secret).contains("https://portal:***@10.10.1.2/api");
        assertThat(orchestrix.baseUrl()).as("the url used to connect").contains(secret);
        Deployment d = Deployment.of(theSampleWith("redis://:" + secret + "@10.10.188.2:6379", "jdbc:postgresql://h/db?password=" + other), "ad-sphere");
        assertThat(d.toString()).doesNotContain(secret).doesNotContain(other);
    }

    @Test
    void theBootstrapsWhere_masksItsUserinfo_itsOriginStaysTheRoadTheTreesConnectBy() {
        HttpBootstrapSource source = new HttpBootstrapSource("http://ops:" + secret + "@10.10.188.2:7300/api/v1/bootstrap/platform/ad-sphere",
            "AD_SPHERE_BOOTSTRAP_TOKEN", name -> other, Duration.ofSeconds(1));

        assertThat(source.where()).doesNotContain(secret).isEqualTo("http://ops:***@10.10.188.2:7300/api/v1/bootstrap/platform/ad-sphere");
        assertThat(source.origin()).as("seed-tenant's trees connect by it").contains(secret);
        BootstrapSource another = new BootstrapSource() {                       // a source of a host's own: its where() is the default
            @Override public Map<String, Object> envelope() { return Map.of(); }
            @Override public String origin() { return "http://ops:" + secret + "@10.10.188.2:7300"; }
        };
        assertThat(another.where()).isEqualTo("http://ops:***@10.10.188.2:7300");
        assertThat(another.origin()).contains(secret);
    }

    @Test
    void theOrchestrixLedgersStartLine_masksItsBaseUrlsPassword() {
        LedgerSettings settings = LedgerSettings.of("http://portal:" + secret + "@127.0.0.1:9/api", "PORTAL_API_TOKEN", "btcl");

        String log = theLogOf(() -> Ledgers.orchestrix(settings, name -> other, dir.resolve("owed-ledger.jsonl"), Clock.systemUTC()));

        assertThat(log).contains("ledger: orchestrix").doesNotContain(secret).contains("http://portal:***@127.0.0.1:9/api");
    }

    /** The pinned sample, btcl's redis and switch-db urls replaced. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> theSampleWith(String redisUrl, String switchDbUrl) {
        Map<String, Object> envelope = new HashMap<>(MapBootstrapSource.read("/bootstrap/deployment.json"));
        Map<String, Object> config = new HashMap<>((Map<String, Object>) envelope.get("config"));
        Map<String, Object> rs = new HashMap<>((Map<String, Object>) config.get("routesphere"));
        Map<String, Object> roots = new HashMap<>((Map<String, Object>) rs.get("root"));
        Map<String, Object> btcl = new HashMap<>((Map<String, Object>) roots.get("btcl"));
        Map<String, Object> switchDb = new HashMap<>((Map<String, Object>) btcl.get("switch-db"));
        switchDb.put("url", switchDbUrl);
        btcl.put("switch-db", switchDb);
        btcl.put("redis", Map.of("url", redisUrl));
        roots.put("btcl", btcl);
        rs.put("root", roots);
        config.put("routesphere", rs);
        envelope.put("config", config);
        return envelope;
    }

    private static String theLogOf(Runnable act) {
        PrintStream before = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try {
            act.run();
        } finally {
            System.setErr(before);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
