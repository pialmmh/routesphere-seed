package com.telcobright.seed.ledgerclient;

import com.sun.net.httpserver.HttpServer;
import com.telcobright.seed.ledgerclient.api.RemoteLedger;
import com.telcobright.seed.ledgerclient.api.RemoteLedgerFault;
import com.telcobright.seed.ledgerclient.dependencies.RemoteLedgers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2 · the client over real HTTP — a JDK HttpServer on 127.0.0.1 (an ephemeral port, this test's alone): the unit is POSTed below the
 * mount with its JSON and the token as {@code Authorization: Bearer …}; an application that does not answer, or answers too late, is a
 * {@link RemoteLedgerFault}.
 */
class HttpTransportTest {

    private HttpServer server;
    private final AtomicReference<String> seen = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ledger/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " | " + exchange.getRequestHeaders().getFirst("Authorization") + " | " + body);
            if (exchange.getRequestURI().getPath().endsWith("/slow/units")) sleep(1_500);
            byte[] answer = "{\"committed\":true,\"repeated\":false,\"keys\":[],\"rows\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(answer); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void aUnitIsPostedBelowTheMount_withItsTokenAsABearer() {
        RemoteLedger ledger = RemoteLedgers.builder().mount("http://127.0.0.1:" + server.getAddress().getPort() + "/ledger").root("btcl")
            .token(() -> "lab-token").timeout(Duration.ofSeconds(2)).build();

        assertThat(ledger.unit("r-1").add("btcl", "packageaccount", 30, "balanceAfter", BigDecimal.ONE).commit()).isInstanceOf(RemoteLedger.Committed.class);
        assertThat(seen.get()).startsWith("POST /ledger/btcl/units | Bearer lab-token | {\"reference\":\"r-1\",\"moves\":[{\"op\":\"add\"");
    }

    @Test
    void anApplicationThatDoesNotAnswer_orAnswersTooLate_isAFault() throws IOException {
        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { closed = probe.getLocalPort(); }
        RemoteLedger nobody = RemoteLedgers.builder().mount("http://127.0.0.1:" + closed + "/ledger/").root("btcl").token(() -> "t").timeout(Duration.ofSeconds(1)).build();
        RemoteLedger slow = RemoteLedgers.builder().mount("http://127.0.0.1:" + server.getAddress().getPort() + "/ledger/").root("slow").token(() -> "t")
            .timeout(Duration.ofMillis(300)).build();

        assertThatThrownBy(() -> nobody.rows("btcl", "packageaccount", Map.of())).isInstanceOf(RemoteLedgerFault.class).hasMessageContaining("did not answer");
        assertThatThrownBy(() -> slow.unit("r-2").add("btcl", "packageaccount", 30, "balanceAfter", BigDecimal.ONE).commit()).isInstanceOf(RemoteLedgerFault.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
