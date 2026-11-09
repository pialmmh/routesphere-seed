package com.telcobright.seed.switchledgerremote;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.PackageAccount;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.dependencies.KitMakers;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledgerremote.api.RemoteSwitchLedger;
import com.telcobright.seed.switchledgerremote.dependencies.RemoteLedgerMaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code SwitchLedgerMakerTest}'s twin for the {@code remote} kind: a root whose {@code ledger.kind = remote} gets a {@link RemoteSwitchLedger}
 * over the words {@code address}, {@code deadline-ms}, {@code token-var}; a missing {@code address} refuses in words; the bearer's variable
 * is read once from the process environment by NAME, and an empty one refuses naming the variable — never a value.
 */
class RemoteLedgerMakerTest {

    @TempDir Path dir;

    private LoopbackLedger loop;

    @AfterEach
    void stop() { if (loop != null) loop.close(); }

    private RootResources btcl(Map<String, Object> words) {
        return new RootResources("btcl", null, new RootResources.KafkaRoad("10.10.188.2:9092", 1, (short) 1), null, new RootResources.Ledger("remote", words), dir);
    }

    private static LedgerMaker.Context context(Function<String, String> environment) {
        Deployment d = new Deployment("platform", "wifi-sphere", "bed", List.of("btcl"), RoutesphereSettings.defaults(), ZoneId.of("Asia/Dhaka"),
            List.of(), Map.of(), "10.10.188.2:9092", null, Map.of(), "e1");
        return new LedgerMaker.Context(d, TenantLookup.of(), Clock.systemUTC(), environment);
    }

    private static LevelAdmission tier(long accountId) {
        Partner partner = new Partner();
        partner.setIdPartner(701);
        PackageAccount account = new PackageAccount();
        account.setId(accountId);
        account.setUom("OTH_ea");
        LevelAdmission level = new LevelAdmission(0, new Tenant("res_2"), partner, account);
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    @Test
    void aRootWhoseLedgerKindIsRemote_getsARemoteSwitchLedger_withTheBearerFromTheNamedVariable() {
        loop = LoopbackLedger.serve(LedgerLab.open().schema("res_2").account("res_2", 11, 1, "OTH_ea", "100"), "res_2");
        Map<String, Object> words = Map.of("address", loop.address(), "deadline-ms", 750, "token-var", "WIFI_LEDGER_TOKEN");
        LedgerPort port = new RemoteLedgerMaker().make(btcl(words), context(name -> "WIFI_LEDGER_TOKEN".equals(name) ? loop.token : null));
        try (RemoteSwitchLedger remote = (RemoteSwitchLedger) port) {
            assertThat(remote.slowestAnswerMs()).isEqualTo(750);
            assertThat(remote.reserve(tier(11), new BigDecimal("10"), "c-1#L0").orElseThrow().balanceAfter()).isEqualByComparingTo("90");
            assertThat(remote.tierLoaded("res_2")).isTrue();
        }
    }

    @Test
    void aMissingAddress_refusesInWords() {
        assertThatThrownBy(() -> new RemoteLedgerMaker().make(btcl(Map.of("token-var", "WIFI_LEDGER_TOKEN")), context(name -> "x")))
            .hasMessageContaining("the root 'btcl' has a remote ledger with no 'address'");
    }

    @Test
    void anEmptyBearerVariable_refusesNamingTheVariable_neverAValue() {
        Map<String, Object> words = Map.of("address", "127.0.0.1:7410", "token-var", "WIFI_LEDGER_TOKEN");
        assertThatThrownBy(() -> new RemoteLedgerMaker().make(btcl(words), context(name -> null)))
            .hasMessageContaining("WIFI_LEDGER_TOKEN").hasMessageContaining("the root 'btcl'");
        assertThatThrownBy(() -> new RemoteLedgerMaker().make(btcl(Map.of("address", "127.0.0.1:7410")), context(name -> null)))
            .hasMessageContaining("the root 'btcl' has a remote ledger with no 'token-var'");
    }

    @Test
    void theClasspathCarriesTheRemoteKind_besideSwitchAndOrchestrix() {
        assertThat(KitMakers.onTheClasspath().ledger("remote")).isInstanceOf(RemoteLedgerMaker.class);
        assertThat(KitMakers.onTheClasspath().ledger("switch").kind()).isEqualTo("switch");
        assertThat(KitMakers.onTheClasspath().ledger("orchestrix").kind()).isEqualTo("orchestrix");
    }
}
