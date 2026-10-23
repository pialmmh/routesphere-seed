package com.telcobright.seed.sessionflow.bootstrap;

import com.sun.net.httpserver.HttpServer;
import com.telcobright.seed.sessionflow.dependencies.BootstrapSources;
import com.telcobright.seed.sessionflow.spi.BootstrapSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-E item 6 · the bootstrap over HTTP (R8): the token from the environment by its variable's NAME, the envelope read, its etag sent back
 * as If-None-Match so a 304 answers the envelope held; a broken answer said in words. prime-context is a JDK HttpServer in this JVM, on an
 * ephemeral loopback port — no service of the box is reached.
 */
class HttpBootstrapSourceTest {

    private HttpServer server;
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    private String serve() throws IOException {
        byte[] body;
        try (InputStream in = getClass().getResourceAsStream("/bootstrap/deployment.json")) { body = in.readAllBytes(); }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/bootstrap/platform/ad-sphere", exchange -> {
            asked.add(exchange.getRequestHeaders().getFirst("Authorization") + " | " + exchange.getRequestHeaders().getFirst("If-None-Match"));
            boolean unchanged = "\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            int code = status.get() != 200 ? status.get() : unchanged ? 304 : 200;
            exchange.sendResponseHeaders(code, code == 200 ? body.length : -1);
            if (code == 200) try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/bootstrap/platform/ad-sphere";
    }

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    void theEnvelopeIsRead_withTheTokenByItsVariable_andA304AnswersTheOneHeld() throws IOException {
        String url = serve();
        BootstrapSource source = BootstrapSources.http(url, "AD_SPHERE_BOOTSTRAP_TOKEN", Map.of("AD_SPHERE_BOOTSTRAP_TOKEN", "t0ken")::get);
        Map<String, Object> first = source.envelope();
        Map<String, Object> second = source.envelope();
        assertThat(first).containsKey("config");
        assertThat(second).as("the 304 answers the envelope held").isSameAs(first);
        assertThat(asked).containsExactly("Bearer t0ken | null", "Bearer t0ken | \"v1\"");
        assertThat(source.origin()).isEqualTo("http://127.0.0.1:" + server.getAddress().getPort());
        assertThat(source.where()).isEqualTo(url).doesNotContain("t0ken");
    }

    @Test
    void anEmptyTokenVariable_andABrokenAnswer_areSaidInWords() throws IOException {
        String url = serve();
        assertThatThrownBy(() -> BootstrapSources.http(url, "AD_SPHERE_BOOTSTRAP_TOKEN", name -> null).envelope())
            .hasMessageContaining("the bootstrap token's variable AD_SPHERE_BOOTSTRAP_TOKEN is empty");
        status.set(500);
        assertThatThrownBy(() -> BootstrapSources.http(url, "T", name -> "x").envelope()).isInstanceOf(BootstrapSource.BootstrapUnavailable.class)
            .hasMessageContaining("prime-context answered HTTP 500");
        assertThatThrownBy(() -> BootstrapSources.http("http://127.0.0.1:1/api/v1/bootstrap/platform/ad-sphere", "T", name -> "x").envelope())
            .isInstanceOf(BootstrapSource.BootstrapUnavailable.class).hasMessageContaining("prime-context did not answer");
    }

    @Test
    void theYmlHoldsTheUrlAndTheTokensVariableName_nothingElse() {
        BootstrapSource nested = BootstrapSources.fromYml(Map.of("prime-context", Map.of("bootstrap-url", "http://10.10.188.2:7300/api/v1/bootstrap/platform/ad-sphere",
            "token-env", "AD_SPHERE_BOOTSTRAP_TOKEN")), name -> "x");
        BootstrapSource flat = BootstrapSources.fromYml(Map.of("prime-context.bootstrap-url", "http://10.10.188.2:7300/api/v1/bootstrap/platform/ad-sphere",
            "prime-context.token-env", "AD_SPHERE_BOOTSTRAP_TOKEN"), name -> "x");
        assertThat(nested.where()).isEqualTo("http://10.10.188.2:7300/api/v1/bootstrap/platform/ad-sphere");
        assertThat(flat.origin()).isEqualTo("http://10.10.188.2:7300");
        assertThatThrownBy(() -> BootstrapSources.fromYml(Map.of(), name -> "x")).hasMessageContaining("names no prime-context.bootstrap-url");
        assertThatThrownBy(() -> BootstrapSources.fromYml(Map.of("prime-context.bootstrap-url", "http://x/y"), name -> "x"))
            .hasMessageContaining("names no prime-context.token-env");
    }
}
