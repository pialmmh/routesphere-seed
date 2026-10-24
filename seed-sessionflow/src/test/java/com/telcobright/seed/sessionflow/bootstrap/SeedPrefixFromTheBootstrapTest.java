package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-G item 5 · the envelope's ONE addition (PC-0022 §9.1): {@code config.routesphere.naming: {seed-root, seed-prefix}} — the reader takes
 * both; {@code res_} when the prefix is not said; a value that is not a name prefix refused at the read, in words.
 */
class SeedPrefixFromTheBootstrapTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> naming(Map<String, Object> naming) {
        Map<String, Object> envelope = new HashMap<>(MapBootstrapSource.read("/bootstrap/deployment.json"));
        Map<String, Object> config = new HashMap<>((Map<String, Object>) envelope.get("config"));
        Map<String, Object> rs = new HashMap<>((Map<String, Object>) config.get("routesphere"));
        rs.put("naming", naming);
        config.put("routesphere", rs);
        envelope.put("config", config);
        return envelope;
    }

    @Test
    void theSeedRootAndItsPrefix_bothRead() {
        Deployment d = Deployment.of(naming(Map.of("seed-root", "btcl", "seed-prefix", "rs_")), "ad-sphere");
        assertThat(d.seedRoot()).isEqualTo("btcl");
        assertThat(d.seedPrefix()).isEqualTo("rs_");
        assertThat(d.tenantNames().tierDb("btcl", null, 2)).isEqualTo("rs_2");
        assertThat(d.tenantNames().tierDb("link3", null, 5)).isEqualTo("link3_res_5");
    }

    @Test
    void aSeedRootWithoutItsPrefix_keepsTheLegacyRes() {
        Deployment d = Deployment.of(naming(Map.of("seed-root", "btcl")), "ad-sphere");
        assertThat(d.tenantNames().tierDb("btcl", null, 2)).isEqualTo("res_2");
    }

    @Test
    void aPrefixThatIsNotANamePrefix_isRefusedAtTheRead() {
        assertThatThrownBy(() -> Deployment.of(naming(Map.of("seed-root", "btcl", "seed-prefix", "Res-")), "ad-sphere"))
            .hasMessageContaining("config.routesphere.naming.seed-prefix").hasMessageContaining("'Res-' is not a name prefix");
    }
}
