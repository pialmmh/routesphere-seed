package com.telcobright.seed.bootstrapconfig;

import com.telcobright.seed.bootstrapconfig.dependencies.BootstrapConfigSourceFactory;
import com.telcobright.seed.sessionflow.dependencies.BootstrapSources;
import com.telcobright.seed.sessionflow.spi.BootstrapSource;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-E item 12 · the application's WHOLE config from the bootstrap (R8): SmallRye Config built by hand — the jar's two lines (the url and
 * the token's variable name) at 250, the factory's source at 275, the environment at 300, {@code -D} at 400 — over a map-backed bootstrap
 * (no network): the app's own keys served flattened (a nested map, a list), the override order, never a secret, the refusals in words.
 */
class BootstrapConfigSourceTest {

    static final String URL = "http://10.10.188.2:7300/api/v1/bootstrap/platform/ad-sphere";
    static final Map<String, String> THE_JARS_TWO_LINES = Map.of(
        "prime-context.bootstrap-url", URL, "prime-context.token-env", "AD_SPHERE_BOOTSTRAP_TOKEN",
        "adsphere.billing.unshown", "refund", "adsphere.pool", "500");

    private final MapBootstrapSource prime = MapBootstrapSource.ofResource("/bootstrap/deployment-with-app-keys.json");
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final ConfigSourceFactory factory = new BootstrapConfigSourceFactory((url, tokenVar) -> { asked.add(url + " | " + tokenVar); return prime; });

    private SmallRyeConfig config(Map<String, String> jar, Map<String, String> environment, Map<String, String> systemProperties) {
        return new SmallRyeConfigBuilder()
            .withSources(new PropertiesConfigSource(jar, "application.yml", 250))
            .withSources(new PropertiesConfigSource(environment, "environment", 300))
            .withSources(new PropertiesConfigSource(systemProperties, "system properties", 400))
            .withSources(factory)
            .build();
    }

    @Test
    void theAppsOwnKeys_comeFromTheBootstrap_aboveTheJar_flattened() {
        SmallRyeConfig c = config(THE_JARS_TWO_LINES, Map.of(), Map.of());
        assertThat(asked).as("the url and the token's variable NAME, read from the lower sources").containsExactly(URL + " | AD_SPHERE_BOOTSTRAP_TOKEN");
        assertThat(c.getValue("adsphere.billing.unshown", String.class)).as("275 over the jar's 250").isEqualTo("charge");
        assertThat(c.getValue("adsphere.pool", String.class)).as("a key the bootstrap does not say: the jar's").isEqualTo("500");
        assertThat(c.getValue("quarkus.http.port", Integer.class)).isEqualTo(7132);
        assertThat(c.getValue("adsphere.media.file.public-base-url", String.class)).isEqualTo("http://10.10.188.2:7132");
        assertThat(c.getValue("adsphere.officer.enabled", Boolean.class)).isTrue();
        assertThat(c.getValue("sdr.dir", String.class)).isEqualTo("/var/lib/ad-sphere/sdr");
        assertThat(c.getValue("routesphere.root.link3.switch-db.schema", String.class)).isEqualTo("link3");
    }

    @Test
    void aListIsIndexed_andAKeyWithADotIsOneSegment() {
        SmallRyeConfig c = config(THE_JARS_TWO_LINES, Map.of(), Map.of());
        assertThat(c.getValue("adsphere.zones[1]", String.class)).isEqualTo("hazrat-shahjalal-airport");
        assertThat(c.getValues("adsphere.zones", String.class)).containsExactly("kamalapur", "hazrat-shahjalal-airport", "dmch");
        assertThat(c.getValue("routesphere.roots[0]", String.class)).isEqualTo("btcl");
        assertThat(c.getValue("routesphere.identification.rules[1].match", String.class)).isEqualTo("10.10.188.2:7071");
        assertThat(c.getValue("quarkus.log.category.\"com.telcobright\".level", String.class)).isEqualTo("INFO");
    }

    @Test
    void theEnvironmentAndTheSystemProperties_stillWin() {
        SmallRyeConfig c = config(THE_JARS_TWO_LINES, Map.of("adsphere.billing.unshown", "refund"), Map.of("quarkus.http.port", "7199"));
        assertThat(c.getValue("adsphere.billing.unshown", String.class)).as("the environment (300) over the bootstrap (275)").isEqualTo("refund");
        assertThat(c.getValue("quarkus.http.port", Integer.class)).as("-D (400) over the bootstrap").isEqualTo(7199);
        assertThat(c.getConfigSources()).anySatisfy(s -> {
            assertThat(s.getName()).startsWith("prime-context bootstrap");
            assertThat(s.getOrdinal()).isEqualTo(275);
        });
    }

    @Test
    void aSecretNeverTravelsAsAConfigValue_aPasswordRefStaysAReference() {
        SmallRyeConfig c = config(THE_JARS_TWO_LINES, Map.of(), Map.of());
        assertThat(c.getValue("reports.password-ref", String.class)).isEqualTo("env:AD_REPORTS_DB_PASSWORD");
        assertThat(c.getValue("routesphere.root.btcl.switch-db.password-ref", String.class)).isEqualTo("env:AD_SWITCH_DB_PASSWORD");
        assertThat(c.getPropertyNames()).noneMatch(name -> name.contains("AD_SWITCH_DB_PASSWORD") || name.startsWith("secrets"));
        for (String name : c.getPropertyNames()) {
            assertThat(c.getOptionalValue(name, String.class)).as(name).isNotEqualTo(java.util.Optional.of("never-a-config-value"));
        }
    }

    @Test
    void noBootstrapUrl_noSource_theApplicationsOwnFilesOnly() {
        SmallRyeConfig c = config(Map.of("adsphere.billing.unshown", "refund"), Map.of(), Map.of());
        assertThat(asked).isEmpty();
        assertThat(c.getValue("adsphere.billing.unshown", String.class)).isEqualTo("refund");
    }

    @Test
    void anUnreachableOrRefusingBootstrap_refusesTheStart_asTheKitsDoorDoes() {
        ConfigSourceFactory unreachable = new BootstrapConfigSourceFactory((url, tokenVar) -> new BootstrapSource() {
            @Override public Map<String, Object> envelope() { throw new BootstrapUnavailable("prime-context did not answer at " + url + ": Connection refused"); }
            @Override public String origin() { return "http://10.10.188.2:7300"; }
        });
        assertThatThrownBy(() -> new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(THE_JARS_TWO_LINES, "application.yml", 250))
                .withSources(unreachable).build())
            .hasStackTraceContaining("prime-context did not answer at " + URL);
        ConfigSourceFactory http = new BootstrapConfigSourceFactory((url, tokenVar) -> BootstrapSources.http(url, tokenVar, name -> null));
        assertThatThrownBy(() -> new SmallRyeConfigBuilder().withSources(new PropertiesConfigSource(Map.of("prime-context.bootstrap-url", URL), "application.yml", 250))
                .withSources(http).build())
            .as("a url without its token's variable name: the kit's own words").hasStackTraceContaining("names no prime-context.token-env");
    }

    @Test
    void theClasspathRegistersTheFactory() {
        assertThat(ServiceLoader.load(ConfigSourceFactory.class)).anySatisfy(f -> assertThat(f).isInstanceOf(BootstrapConfigSourceFactory.class));
    }
}
