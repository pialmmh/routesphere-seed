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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-J item 2 · the switch ledger connects with its OWN login when the root names one ({@code ledger.db}: separation of duties at the
 * database — the app's role keeps the app's tables, the ledger's role the money tables; ONE writer of money per root), else with switch-db's
 * as before. Its password is read from the PROCESS environment only (the secrets ruling), never the envelope's secrets.
 */
class LedgerOwnLoginTest {

    @TempDir Path dir;
    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("btcl_res_5");
    private final TenantLookup trees = TenantLookup.of(new TenantTreeBuilder().root("btcl").partner(1, "client", PartnerType.CUSTOMER)
        .partner(5, "Res5", PartnerType.RESELLER).and().tenant("btcl_res_5", "btcl").partner(501, "Res5 client", PartnerType.CUSTOMER).and().build());
    private final List<String> logins = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() throws Exception { lab.close(); }

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

    private RootResources btcl(RootResources.SwitchDb ledgerDb) {
        RootResources.SwitchDb switchDb = new RootResources.SwitchDb(lab.url(), "btcl", "ad_sphere", "env:AD_SWITCH_DB_PASSWORD");
        return new RootResources("btcl", switchDb, new RootResources.KafkaRoad("10.10.188.2:9092", 1, (short) 1), null,
            new RootResources.Ledger(RootResources.Ledger.SWITCH, Map.of()), dir, ledgerDb);
    }

    private LedgerMaker.Context context(Map<String, String> environment, Map<String, String> secrets) {
        Deployment d = new Deployment("btcl", "ad-sphere", "bed", List.of("btcl"), RoutesphereSettings.defaults(), ZoneId.of("Asia/Dhaka"),
            List.of(), Map.of(), "10.10.188.2:9092", null, secrets, "e1");
        return new LedgerMaker.Context(d, trees, Clock.systemUTC(), environment::get);
    }

    @Test
    void givenBothLogins_theLedgerConnectsWithItsOwn() throws Exception {
        RootResources.SwitchDb own = new RootResources.SwitchDb(null, null, "btcl_ledger", "env:BTCL_LEDGER_DB_PASSWORD");
        LedgerPort port = recordingMaker().make(btcl(own), context(Map.of("BTCL_LEDGER_DB_PASSWORD", "ledger-pw", "AD_SWITCH_DB_PASSWORD", "app-pw"), Map.of()));
        ((AutoCloseable) port).close();

        assertThat(logins).containsExactly("btcl_ledger@" + lab.url() + "/btcl password ledger-pw");
    }

    @Test
    void withoutItsOwn_theLedgerConnectsWithSwitchDbs_asBefore() throws Exception {
        LedgerPort port = recordingMaker().make(btcl(null), context(Map.of("AD_SWITCH_DB_PASSWORD", "app-pw"), Map.of()));
        ((AutoCloseable) port).close();

        assertThat(logins).containsExactly("ad_sphere@" + lab.url() + "/btcl password app-pw");
    }

    @Test
    void theLedgersOwnPassword_comesFromTheEnvironmentOnly_neverTheEnvelopesSecrets() {
        RootResources.SwitchDb own = new RootResources.SwitchDb(null, null, "btcl_ledger", "env:BTCL_LEDGER_DB_PASSWORD");

        assertThatThrownBy(() -> recordingMaker().make(btcl(own), context(Map.of(), Map.of("BTCL_LEDGER_DB_PASSWORD", "from-secrets"))))
            .hasMessageContaining("BTCL_LEDGER_DB_PASSWORD").hasMessageContaining("environment");
        assertThat(logins).as("never connected").isEmpty();
    }
}
