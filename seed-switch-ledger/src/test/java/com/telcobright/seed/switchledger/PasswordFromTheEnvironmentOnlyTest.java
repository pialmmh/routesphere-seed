package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerMaker;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-K item 1 · a password comes from the PROCESS environment only (the owner's ruling of 2026-10-24, "Environment only", which re-affirms
 * his rule of 2026-09-24: secreteer fills the box's environment file; an application reads only its environment). switch-db's password by
 * reference was read from the environment, then the bootstrap envelope's {@code secrets} (P1-E); now from the environment only, as the
 * ledger's own login already was (P1-J). A variable not in the environment refuses the root's ledger in words naming the VARIABLE, never a
 * value — and nothing is connected. The passwords here are generated, in the test's own maps.
 */
class PasswordFromTheEnvironmentOnlyTest {

    @TempDir Path dir;
    private final LedgerLab lab = LedgerLab.open().schema("btcl");
    private final TenantLookup trees = TenantLookup.of(new TenantTreeBuilder().root("btcl").partner(1, "client", PartnerType.CUSTOMER).and().build());
    private final List<String> logins = new CopyOnWriteArrayList<>();
    private final String inTheSecrets = "pw-" + UUID.randomUUID();
    private final String inTheEnvironment = "pw-" + UUID.randomUUID();

    @AfterEach
    void close() throws Exception { lab.close(); }

    @Test
    void aSwitchDbPasswordOnlyInTheEnvelopesSecrets_refusesTheRootsLedger_inWordsNamingTheVariable_neverConnected() {
        assertThatThrownBy(() -> recordingMaker().make(btcl(), context(Map.of(), Map.of("AD_SWITCH_DB_PASSWORD", inTheSecrets))))
            .hasMessageContaining("AD_SWITCH_DB_PASSWORD").hasMessageContaining("not in the process environment")
            .hasMessageNotContaining(inTheSecrets);
        assertThat(logins).as("never connected").isEmpty();
    }

    @Test
    void aSwitchDbPasswordInTheEnvironment_isTheOneRead_whateverTheSecretsSay() throws Exception {
        LedgerPort port = recordingMaker().make(btcl(),
            context(Map.of("AD_SWITCH_DB_PASSWORD", inTheEnvironment), Map.of("AD_SWITCH_DB_PASSWORD", inTheSecrets)));
        ((AutoCloseable) port).close();

        assertThat(logins).containsExactly("ad_sphere@" + lab.url() + "/btcl password " + inTheEnvironment);
    }

    /** The maker's DataSources: each login it was asked for written down; the lab's H2 given back whatever the login. */
    private SwitchLedgerMaker recordingMaker() {
        return new SwitchLedgerMaker((db, password) -> {
            logins.add(db.username() + "@" + db.url() + "/" + db.schema() + " password " + password);
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL(lab.url());
            h2.setUser("sa");
            h2.setPassword("");
            return h2;
        });
    }

    /** btcl as the pinned sample serves it: switch-db with the app's login, its password by reference; no ledger login of its own. */
    private RootResources btcl() {
        return new RootResources("btcl", new RootResources.SwitchDb(lab.url(), "btcl", "ad_sphere", "env:AD_SWITCH_DB_PASSWORD"),
            new RootResources.KafkaRoad("10.10.188.2:9092", 1, (short) 1), null, new RootResources.Ledger(RootResources.Ledger.SWITCH, Map.of()), dir);
    }

    /** A deployment whose envelope carried {@code secrets}, and the process environment the maker is given. */
    private LedgerMaker.Context context(Map<String, String> environment, Map<String, String> secrets) {
        Deployment d = new Deployment("btcl", "ad-sphere", "bed", List.of("btcl"), RoutesphereSettings.defaults(), ZoneId.of("Asia/Dhaka"),
            List.of(), Map.of(), "10.10.188.2:9092", null, secrets, "e1");
        return new LedgerMaker.Context(d, trees, Clock.systemUTC(), environment::get);
    }
}
