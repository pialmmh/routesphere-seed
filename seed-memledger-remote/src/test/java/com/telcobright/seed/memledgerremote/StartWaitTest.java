package com.telcobright.seed.memledgerremote;

import com.telcobright.rtc.domainmodel.PartnerType;
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
 * Item (d) of the remote-fixes round · recall-db's unit is Type=simple: "started" is not "serving" (its WAL replay and cache load come
 * first), so a client started right after it can miss its first call — seen 1 of 4 cold rounds at 1000 ms. The start WAITS for the
 * server's first answer: tried again with a short backoff until it answers or {@code start-wait-ms} passes (30 s unless said), each failed
 * try said once, then refused in words. A refused bearer is an answer: refused at once, never waited for.
 */
class StartWaitTest {

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
    void aServerThatStartsThreeSecondsAfterItsClient_isWaitedFor_andTheStartSucceeds() {
        int port = aFreePort();
        Thread.ofPlatform().daemon().start(() -> {
            pause(3_000);
            opened.add(new StubServer(lab.ledger(), port, token));
        });

        long startedAt = System.nanoTime();
        LedgerPort ledger = made(words(port, null), token);
        opened.add((AutoCloseable) ledger);
        long tookMs = (System.nanoTime() - startedAt) / 1_000_000;

        System.out.println("StartWaitTest: the start answered after " + tookMs + " ms (the server came up at 3000 ms)");
        assertThat(tookMs).as("it waited for the server's first answer").isGreaterThanOrEqualTo(3_000).isLessThan(30_000);
        assertThat(ledger.slowestAnswerMs()).as("a ledger over the server, its deadline").isEqualTo(1_000);
    }

    @Test
    void aServerThatNeverAnswers_refusesTheStartInWords_onceStartWaitMsPassed() {
        int port = aFreePort();
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> made(words(port, "1500"), token))
            .hasMessageContaining("the root 'btcl': its ledger does not start")
            .hasMessageContaining("the mem-ledger server at 127.0.0.1:" + port)
            .hasMessageContaining("start-wait-ms 1500");
        assertThat((System.nanoTime() - startedAt) / 1_000_000).as("refused only once start-wait-ms passed").isGreaterThanOrEqualTo(1_500);
    }

    @Test
    void aRefusedBearer_isAnAnswer_refusedAtOnce() {
        StubServer server = new StubServer(lab.ledger(), 0, token);
        opened.add(server);
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> made(words(Integer.parseInt(server.address().split(":")[1]), null), "not-" + token))
            .hasMessageContaining("refused the bearer in LAB_LEDGER_TOKEN");
        assertThat((System.nanoTime() - startedAt) / 1_000_000).as("never the 30 s of start-wait-ms").isLessThan(5_000);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, Object> words(int port, String startWaitMs) {
        Map<String, Object> words = new HashMap<>(Map.of("store", "remote", "address", "127.0.0.1:" + port, "deadline-ms", "1000", "token-var", "LAB_LEDGER_TOKEN"));
        if (startWaitMs != null) words.put("start-wait-ms", startWaitMs);
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

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
