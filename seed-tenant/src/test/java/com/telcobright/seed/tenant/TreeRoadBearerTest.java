package com.telcobright.seed.tenant;

import com.sun.net.httpserver.HttpServer;
import com.telcobright.seed.sessionflow.api.Deployment;
import com.telcobright.seed.sessionflow.dependencies.BootstrapSources;
import com.telcobright.seed.sessionflow.dependencies.RoutesphereSettings;
import com.telcobright.seed.sessionflow.spi.BootstrapSource;
import com.telcobright.seed.sessionflow.spi.LiveTrees;
import com.telcobright.seed.tenant.dependencies.PrimeContextTrees;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-O item 6 (the architect's A9) · The deployed trees ask prime-context's tree road ({@code get-specific-tenant-root}) with the SAME token
 * the bootstrap asks the envelope with — {@code Authorization: Bearer …} whenever the kit has a token, no header when it has none, a rotated
 * token taken at the next ask — so prime-context may close its tree roads (PC-0025 item 3). The token is never printed. prime-context here
 * is a JDK HttpServer in this JVM on 127.0.0.1; the deployment names no platform Kafka, so no doorbell is opened.
 */
class TreeRoadBearerTest {

    static final String TOKEN_VAR = "AD_SPHERE_BOOTSTRAP_TOKEN";

    private HttpServer server;
    private LiveTrees trees;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final Map<String, String> environment = new HashMap<>();

    @AfterEach
    void stop() {
        if (trees != null) trees.close();
        if (server != null) server.stop(0);
    }

    /** prime-context's bootstrap url on this JVM's server; its tree road answers every root with TreesTest's wire tree. */
    private String primeContext() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/get-specific-tenant-root", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] tree = TreesTest.WIRE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), status.get() == 200 ? tree.length : -1);
            if (status.get() == 200) try (OutputStream out = exchange.getResponseBody()) { out.write(tree); }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/bootstrap/platform/ad-sphere";
    }

    private static Deployment serving(String root) {
        return new Deployment("platform", "ad-sphere", "lab", List.of(root), RoutesphereSettings.defaults(), ZoneId.of("Asia/Dhaka"), List.of(),
            Map.of(), "", null, Map.of(), "e1");
    }

    private LiveTrees deployedTrees(BootstrapSource bootstrap) { return trees = new PrimeContextTrees().make(serving("btcl"), bootstrap); }

    @Test
    void the_tree_road_is_asked_with_the_bootstraps_own_token_and_a_rotated_token_at_the_next_ask() throws IOException {
        String url = primeContext();
        environment.put(TOKEN_VAR, "tree-road-token-1");
        LiveTrees live = deployedTrees(BootstrapSources.http(url, TOKEN_VAR, environment::get));
        assertThat(authorizations).containsExactly("Bearer tree-road-token-1");
        assertThat(live.snapshot("btcl")).isPresent();

        environment.put(TOKEN_VAR, "tree-road-token-2");                          // the secreteer rotated it
        live.reload("btcl");
        assertThat(authorizations).containsExactly("Bearer tree-road-token-1", "Bearer tree-road-token-2");
    }

    @Test
    void a_kit_with_no_token_asks_with_no_header_and_a_refusal_never_prints_the_token() throws IOException {
        String url = primeContext();
        BootstrapSource noToken = new BootstrapSource() {
            @Override public Map<String, Object> envelope() { throw new IllegalStateException("not asked here"); }
            @Override public String origin() { return url.substring(0, url.indexOf("/api/")); }
        };
        deployedTrees(noToken);
        assertThat(authorizations).containsExactly("null");

        trees.close();
        trees = null;
        authorizations.clear();
        status.set(401);
        environment.put(TOKEN_VAR, "tree-road-token-3");
        assertThatThrownBy(() -> deployedTrees(BootstrapSources.http(url, TOKEN_VAR, environment::get)))
            .satisfies(refused -> assertThat(String.valueOf(refused) + refused.getCause()).doesNotContain("tree-road-token-3"));
        assertThat(authorizations).containsExactly("Bearer tree-road-token-3");
    }
}
