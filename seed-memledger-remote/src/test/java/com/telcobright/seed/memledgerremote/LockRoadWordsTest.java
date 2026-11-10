package com.telcobright.seed.memledgerremote;

import com.telcobright.rtc.domainmodel.PartnerType;
import com.telcobright.seed.memledgerremote.api.Capabilities;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.api.RootResources;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.LedgerMaker;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import com.telcobright.seed.sessionflow.spi.TenantLookup;
import com.telcobright.seed.sessionflow.testkit.TenantTreeBuilder;
import com.telcobright.seed.switchledger.LedgerLab;
import com.telcobright.seed.switchledger.dependencies.SwitchLedgerMaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ARCH-0119 road 2 · the lock road's WORDS of a root: {@code lock-road} is {@code "on"} or {@code "off"} (off unless said),
 * {@code lock-lease-ms} 1–1000, {@code lock-wait-ms} 0 or more — anything else refuses the start in words naming the root and the
 * word. And a root that says ON over a server that does not serve the lock road does NOT start: never a silent unlocked ledger.
 */
class LockRoadWordsTest {

    @TempDir Path dir;

    private final LedgerLab lab = LedgerLab.open().schema("btcl").schema("btcl_res_5");
    private final TenantLookup trees = TenantLookup.of(new TenantTreeBuilder().root("btcl").partner(1, "client", PartnerType.CUSTOMER)
        .partner(5, "Res5", PartnerType.RESELLER).and().tenant("btcl_res_5", "btcl").partner(501, "Res5 client", PartnerType.CUSTOMER).and().build());
    private final String token = UUID.randomUUID().toString();
    private final List<AutoCloseable> opened = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startTheLab() { lab.start(); }

    @AfterEach
    void closeEverything() throws Exception {
        for (AutoCloseable c : opened) c.close();
        lab.close();
    }

    @Test
    void notSaid_orOff_theRootStartsOverAServerThatDoesNotServeTheLockRoad() {
        StubServer older = new StubServer(lab.ledger(), 0, token);
        opened.add(older);

        opened.add((AutoCloseable) made(words(older.port(), null), token));
        opened.add((AutoCloseable) made(words(older.port(), "off"), token));
    }

    @Test
    void onOverAServerThatDoesNotServeIt_refusesTheStartInWords() {
        StubServer older = new StubServer(lab.ledger(), 0, token);
        opened.add(older);

        assertThatThrownBy(() -> made(words(older.port(), "on"), token))
            .hasMessageContaining("ledger.lock-road is \"on\" and the mem-ledger server at 127.0.0.1:" + older.port() + " does not serve the lock road")
            .hasMessageContaining("or say lock-road: \"off\"");
    }

    @Test
    void onOverAServerThatSaysLease_theRootStarts() {
        StubServer serves = new StubServer(lab.ledger(), 0, token, List.of(Capabilities.EXPECT, Capabilities.LEASE));
        opened.add(serves);

        opened.add((AutoCloseable) made(words(serves.port(), "on"), token));
    }

    @Test
    void aWordThatIsNotOnOrOff_orALeaseOutOfItsRange_refusesTheStartInWords_namingTheRootAndTheWord() {
        assertThatThrownBy(() -> made(words(aFreePort(), "true"), token))
            .hasMessageContaining("the root 'btcl': ledger.lock-road is \"on\" or \"off\" (in quotes), not 'true'");
        Map<String, Object> longLease = words(aFreePort(), "on");
        longLease.put("lock-lease-ms", "1500");
        assertThatThrownBy(() -> made(longLease, token)).hasMessageContaining("the root 'btcl': ledger.lock-lease-ms is 1 to 1000 milliseconds, not 1500");
        Map<String, Object> noLease = words(aFreePort(), "on");
        noLease.put("lock-lease-ms", "0");
        assertThatThrownBy(() -> made(noLease, token)).hasMessageContaining("the root 'btcl': ledger.lock-lease-ms is a whole number of milliseconds (1 to 1000), not '0'");
        Map<String, Object> badWait = words(aFreePort(), "on");
        badWait.put("lock-wait-ms", "soon");
        assertThatThrownBy(() -> made(badWait, token)).hasMessageContaining("the root 'btcl': ledger.lock-wait-ms is a whole number of milliseconds (0 or more), not 'soon'");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Object> words(int port, String lockRoad) {
        Map<String, Object> words = new HashMap<>(Map.of("store", "remote", "address", "127.0.0.1:" + port, "deadline-ms", "1000", "start-wait-ms", "2000", "token-var", "LAB_LEDGER_TOKEN"));
        if (lockRoad != null) words.put("lock-road", lockRoad);
        return words;
    }

    private LedgerPort made(Map<String, Object> ledgerWords, String bearer) {
        RootResources btcl = new RootResources("btcl", new RootResources.SwitchDb(lab.url(), "btcl", "sa", null), new RootResources.KafkaRoad("10.10.188.2:9092", 1, (short) 1),
            null, new RootResources.Ledger(RootResources.Ledger.SWITCH, ledgerWords), dir);
        Deployment d = new Deployment("platform", "ad-sphere", "bed", List.of("btcl"), RoutesphereSettings.defaults(), ZoneId.of("Asia/Dhaka"),
            List.of(), Map.of(), "10.10.188.2:9092", null, Map.of(), "e1");
        return new SwitchLedgerMaker().make(btcl, new LedgerMaker.Context(d, trees, Clock.systemUTC(), Map.of("LAB_LEDGER_TOKEN", bearer)::get));
    }

    private static int aFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
