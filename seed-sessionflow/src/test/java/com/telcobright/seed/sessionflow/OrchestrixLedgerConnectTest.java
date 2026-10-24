package com.telcobright.seed.sessionflow;

import com.telcobright.rtc.domainmodel.LevelAdmission;
import com.telcobright.rtc.domainmodel.mysqlentity.Partner;
import com.telcobright.rtc.domainmodel.nonentity.Tenant;
import com.telcobright.seed.sessionflow.dependencies.LedgerSettings;
import com.telcobright.seed.sessionflow.dependencies.Ledgers;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-I item 1 · a connect that cannot complete says WHICH clock cut it. Java's HttpClient applies the request's own timeout to the connect
 * phase too, so a connect can be cut by the time the admission had left, by the ledger's read timeout (when it is the shorter clock), or
 * by the configured connect timeout — each said in its own words. Nothing was sent in any of them: always a {@code LedgerFault}, the money
 * certain. The ledger here is a local port whose accept queue is full (backlog 1, never accepted): the kernel drops the SYN, the connect
 * hangs.
 */
class OrchestrixLedgerConnectTest {

    /** A local port a connect cannot complete to: its accept queue filled and never accepted. */
    static final class NoAccept implements AutoCloseable {
        final ServerSocket server;
        final List<Socket> fillers = new ArrayList<>();

        NoAccept() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            for (int i = 0; i < 32; i++) {
                Socket filler = new Socket();
                try {
                    filler.connect(server.getLocalSocketAddress(), 300);
                    fillers.add(filler);
                } catch (SocketTimeoutException full) {
                    filler.close();
                    return;                                                        // the queue is full: the next connect hangs too
                }
            }
            throw new IllegalStateException("the accept queue of a backlog-1 socket never filled: this box completes every connect");
        }

        String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/"; }

        @Override
        public void close() throws IOException {
            for (Socket s : fillers) s.close();
            server.close();
        }
    }

    @TempDir Path dir;
    private NoAccept ledgerThatNeverAccepts;
    private final Function<String, String> env = Map.of("PORTAL_API_TOKEN", "t0ken-not-a-secret")::get;

    @BeforeEach
    void up() throws IOException { ledgerThatNeverAccepts = new NoAccept(); }

    @AfterEach
    void down() throws IOException { ledgerThatNeverAccepts.close(); }

    private LedgerPort ledger(long connectTimeoutMs, long readTimeoutMs) {
        LedgerSettings settings = new LedgerSettings(ledgerThatNeverAccepts.url(), "PORTAL_API_TOKEN", "btcl", "ad-credit", connectTimeoutMs, readTimeoutMs, "ad-sphere");
        return Ledgers.orchestrix(settings, env, dir.resolve("owed-ledger.jsonl"), Clock.fixed(Instant.parse("2026-10-24T08:00:00Z"), ZoneOffset.UTC));
    }

    private static LevelAdmission level() {
        Partner partner = new Partner();
        partner.setIdPartner(701);
        partner.setPartnerName("Unilever");
        partner.setBillingAccountId("9001");
        LevelAdmission level = new LevelAdmission(0, new Tenant("res_44"), partner, null);
        level.setUsageKind("video");
        level.setUsageSeconds(15);
        level.setReservedAmount(BigDecimal.ZERO);
        return level;
    }

    @Test
    void aConnectTheAdmissionsBudgetCut_saysTheBudget_andIsALedgerFault() {
        LedgerPort ledger = ledger(30_000, 5_000);                                     // its own clocks: connect 30 s, read 5 s
        long startedMs = System.currentTimeMillis();

        assertThatThrownBy(() -> ledger.reserve(level(), new BigDecimal("0.50"), "ad-21#L0", 400))
            .isInstanceOf(LedgerPort.LedgerFault.class)
            .hasMessageMatching(".*no connection within the \\d+ ms the admission had left.*")
            .hasMessageNotContaining("30000 ms");

        assertThat(System.currentTimeMillis() - startedMs).as("cut at the budget, never at its own 30 s").isLessThan(10_000);
        assertThat(dir.resolve("owed-ledger.jsonl")).as("nothing was sent: the money is certain, no unsure line").doesNotExist();
    }

    @Test
    void aConnectTheReadTimeoutCut_saysTheReadTimeout() {
        LedgerPort ledger = ledger(30_000, 400);                                       // the read timeout the shorter clock; time to spare

        assertThatThrownBy(() -> ledger.reserve(level(), new BigDecimal("0.50"), "ad-22#L0", 60_000))
            .isInstanceOf(LedgerPort.LedgerFault.class)
            .hasMessageContaining("no connection within its 400 ms read timeout")
            .hasMessageNotContaining("30000 ms");
    }

    @Test
    void aConnectItsOwnConnectTimeoutCut_keepsItsWords() {
        LedgerPort ledger = ledger(400, 5_000);                                        // the configured connect timeout the shorter clock

        assertThatThrownBy(() -> ledger.reserve(level(), new BigDecimal("0.50"), "ad-23#L0", 60_000))
            .isInstanceOf(LedgerPort.LedgerFault.class)
            .hasMessageContaining("no connection within 400 ms");
    }
}
