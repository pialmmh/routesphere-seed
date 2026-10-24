package com.telcobright.seed.sessionflow.bootstrap;

import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.testkit.MapBootstrapSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-J item 2 · the switch ledger's OWN login (ad-sphere P2c's G3): prime-context grants the app's role (its tables) and the switch ledger's
 * role (the money tables) separately and never one role for both. An OPTIONAL {@code ledger.db {user, password-ref[, url, schema]}} for a
 * root whose ledger is the switch ledger — url and schema switch-db's when not said; absent = switch-db's login, as before. The sample is a
 * NEW file ({@code deployment-with-ledger-login.json}); the pinned §C files stay byte-identical with prime-context's.
 */
class LedgerLoginFromTheBootstrapTest {

    private static Map<String, Object> sample() { return MapBootstrapSource.read("/bootstrap/deployment-with-ledger-login.json"); }

    @Test
    void aRootWithItsLedgersOwnLogin_readsIt_itsPlaceSwitchDbs() {
        RootResources btcl = Deployment.of(sample(), "ad-sphere").root("btcl").orElseThrow();

        assertThat(btcl.ledgerDb().username()).isEqualTo("btcl_ledger");
        assertThat(btcl.ledgerDb().passwordRef()).isEqualTo("env:BTCL_LEDGER_DB_PASSWORD");
        assertThat(btcl.ledgerLogin().username()).as("the ledger connects as its own role").isEqualTo("btcl_ledger");
        assertThat(btcl.ledgerLogin().url()).as("its url switch-db's when not said").isEqualTo("jdbc:postgresql://10.10.188.2:5434/routesphere");
        assertThat(btcl.ledgerLogin().schema()).isEqualTo("btcl");
        assertThat(btcl.switchDb().username()).as("switch-db's login is the app's, as it was").isEqualTo("ad_sphere");
    }

    @Test
    void aRootWithout_connectsWithSwitchDbsLogin_asBefore() {
        RootResources link3 = Deployment.of(sample(), "ad-sphere").root("link3").orElseThrow();
        assertThat(link3.ledgerDb()).isNull();
        assertThat(link3.ledgerLogin()).isSameAs(link3.switchDb());
        assertThat(Deployment.of(MapBootstrapSource.read("/bootstrap/deployment.json"), "ad-sphere").root("btcl").orElseThrow().ledgerDb())
            .as("the pinned sample: no ledger.db").isNull();
    }

    @Test
    void aLedgerLoginWithoutItsUser_orOnAnOrchestrixLedger_isRefusedInWords() {
        assertThatThrownBy(() -> Deployment.of(withBtclLedger(Map.of("kind", "switch", "db", Map.of("password-ref", "env:X"))), "ad-sphere"))
            .hasMessageContaining("config.routesphere.root.btcl.ledger.db names no user");
        assertThatThrownBy(() -> Deployment.of(withBtclLedger(Map.of("kind", "orchestrix", "url", "http://portal", "db", Map.of("user", "u"))), "ad-sphere"))
            .hasMessageContaining("ledger.db is the switch ledger's own login");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withBtclLedger(Map<String, Object> ledger) {
        Map<String, Object> envelope = new HashMap<>(MapBootstrapSource.read("/bootstrap/deployment.json"));
        Map<String, Object> config = new HashMap<>((Map<String, Object>) envelope.get("config"));
        Map<String, Object> rs = new HashMap<>((Map<String, Object>) config.get("routesphere"));
        Map<String, Object> roots = new HashMap<>((Map<String, Object>) rs.get("root"));
        Map<String, Object> btcl = new HashMap<>((Map<String, Object>) roots.get("btcl"));
        btcl.put("ledger", ledger);
        roots.put("btcl", btcl);
        rs.put("root", roots);
        config.put("routesphere", rs);
        envelope.put("config", config);
        return envelope;
    }
}
