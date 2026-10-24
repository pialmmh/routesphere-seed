package com.telcobright.seed.switchledger;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.dependencies.KitMakers;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerMaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-E items 6 and 9 · the {@code switch} ledger of a root, made from its bootstrap resources (R12: package-account + mem-ledger, the
 * default): a REAL MemLedger over the root's switch database (H2 in memory, as the lab's), loading the two tables of every tier schema of the
 * root's tree, its WAL and queue in the root's own directory; the base's ledger port over it. Found on the classpath by its kind.
 */
class SwitchLedgerMakerTest {

    @TempDir Path dir;
    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("btcl_res_5");
    private final TenantLookup trees = TenantLookup.of(new TenantTreeBuilder().root("btcl").partner(1, "client", PartnerType.CUSTOMER)
        .partner(5, "Res5", PartnerType.RESELLER).and().tenant("btcl_res_5", "btcl").partner(501, "Res5 client", PartnerType.CUSTOMER).and().build());

    @AfterEach
    void close() throws Exception { lab.close(); }

    private RootResources btcl(RootResources.SwitchDb db) {
        return new RootResources("btcl", db, new RootResources.KafkaRoad("10.10.188.2:9092", 1, (short) 1), null,
            new RootResources.Ledger(RootResources.Ledger.SWITCH, Map.of()), dir);
    }

    private LedgerMaker.Context context(TenantLookup lookup) {
        Deployment d = new Deployment("platform", "ad-sphere", "bed", List.of("btcl"), RoutesphereSettings.defaults(), ZoneId.of("Asia/Dhaka"),
            List.of(), Map.of(), "10.10.188.2:9092", null, Map.of(), "e1");
        return new LedgerMaker.Context(d, lookup, Clock.systemUTC(), name -> null);
    }

    @Test
    void theRootsLedger_isAMemLedgerPort_overEveryTierSchemaOfItsTree_itsStateInTheRootsDirectory() throws Exception {
        LedgerPort port = new SwitchLedgerMaker().make(btcl(new RootResources.SwitchDb(lab.url(), "btcl", "sa", null)), context(trees));
        try (AutoCloseable owned = (AutoCloseable) port) {
            assertThat(Files.isDirectory(dir.resolve("ledger"))).as("the WAL and the queue in the root's own directory").isTrue();
        }
    }

    @Test
    void closingTheRootsLedger_shutsItsMemLedgerDown() throws Exception {
        long before = walConsumers();
        LedgerPort port = new SwitchLedgerMaker().make(btcl(new RootResources.SwitchDb(lab.url(), "btcl", "sa", null)), context(trees));
        assertThat(walConsumers()).as("the MemLedger the maker opened runs its WAL consumer").isEqualTo(before + 1);
        ((AutoCloseable) port).close();
        long until = System.currentTimeMillis() + 5_000;
        while (walConsumers() > before && System.currentTimeMillis() < until) Thread.sleep(10);
        assertThat(walConsumers()).as("closed with the port: no thread left behind").isEqualTo(before);
    }

    private static long walConsumers() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && "MemLedger-WAL-Consumer".equals(t.getName())).count();
    }

    @Test
    void aRootWithoutItsSwitchDatabase_orWithoutItsTree_isRefusedInWords() {
        assertThatThrownBy(() -> new SwitchLedgerMaker().make(btcl(null), context(trees))).hasMessageContaining("the root 'btcl' has the switch ledger but no switch-db.url");
        assertThatThrownBy(() -> new SwitchLedgerMaker().make(btcl(new RootResources.SwitchDb(lab.url(), "btcl", "sa", null)), context(TenantLookup.of())))
            .hasMessageContaining("the root 'btcl' has no tree");
    }

    /**
     * P1-K · the pin moved (the owner's ruling of 2026-10-24, "Environment only"): P1-E's "the environment, then the bootstrap's secrets" is
     * gone — a password by reference is read from the process environment ONLY; a missing variable is refused naming it, and a password-ref
     * that is not {@code env:<NAME>} is refused without repeating its text (it may be a password). The passwords are generated.
     */
    @Test
    void aPasswordByReference_isReadFromTheEnvironmentOnly_neverWritten_neverRepeated() {
        String password = "pw-" + UUID.randomUUID();
        RootResources.SwitchDb db = new RootResources.SwitchDb(lab.url(), "btcl", "sa", "env:AD_SWITCH_DB_PASSWORD");
        assertThat(db.passwordFromTheEnvironment(name -> name.equals("AD_SWITCH_DB_PASSWORD") ? password : null)).isEqualTo(password);
        assertThatThrownBy(() -> db.passwordFromTheEnvironment(name -> null))
            .hasMessageContaining("AD_SWITCH_DB_PASSWORD").hasMessageContaining("not in the process environment");
        String written = "pw-" + UUID.randomUUID();
        assertThatThrownBy(() -> new RootResources.SwitchDb(lab.url(), "btcl", "sa", written).passwordFromTheEnvironment(name -> null))
            .hasMessageContaining("is not env:<NAME>").hasMessageNotContaining(written);
    }

    @Test
    void theClasspathCarriesTheSwitchKind() {
        assertThat(KitMakers.onTheClasspath().ledger("switch")).isInstanceOf(SwitchLedgerMaker.class);
        assertThat(KitMakers.onTheClasspath().ledger("orchestrix").kind()).isEqualTo("orchestrix");
    }
}
