package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-K item 2 · the seed reads NO secret from the bootstrap (the owner's ruling of 2026-10-24, "Environment only", re-affirming his rule of
 * 2026-09-24): a password by reference comes from the process environment only — the box's environment file, which the secreteer fills.
 * The envelope's {@code secrets} are still parsed ({@code Deployment.secrets()}: deprecated, read by nobody), but a deployment never says
 * their values — its text names them only. The value here is generated.
 */
class NoSecretFromTheBootstrapTest {

    @Test
    void aDeploymentsText_namesTheEnvelopesSecrets_neverTheirValues() {
        String switchDb = "pw-" + UUID.randomUUID(), reports = "pw-" + UUID.randomUUID();
        Deployment d = Deployment.of(theSampleWithSecrets(Map.of("AD_SWITCH_DB_PASSWORD", switchDb, "AD_REPORTS_DB_PASSWORD", reports)), "ad-sphere");

        assertThat(d.toString()).as("named (AD_REPORTS_DB_PASSWORD is named nowhere else in the sample)").contains("AD_REPORTS_DB_PASSWORD")
            .doesNotContain(switchDb).doesNotContain(reports);
    }

    /** The pinned sample, its {@code secrets} replaced by these. */
    private static Map<String, Object> theSampleWithSecrets(Map<String, String> secrets) {
        Map<String, Object> envelope = new HashMap<>(MapBootstrapSource.read("/bootstrap/deployment.json"));
        envelope.put("secrets", secrets);
        return envelope;
    }
}
