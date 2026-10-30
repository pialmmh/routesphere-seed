package com.telcobright.seed.idgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One tenant-first bucket-next shard in the test, on 127.0.0.1: the five roads the client uses under {@code /api/v2/{tenant}/…} with
 * the service's shard math (shard k of N hands out k, k+N, …), one counter per (tenant, entity), its type binding, its init snapped
 * forward, its forward-only reset, its tenant refusals in the service's words (400, never 404) — and the faults a test asks for. The
 * pre-tenant roads ({@code /api/{road}/…}) answer 400 {@code Tenant is required}, as the service does without {@code legacy_tenant}.
 */
final class StubShard implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern V2 = Pattern.compile("/api/v2/([^/]*)/([^/]+)(?:/([^/]+))?");
    private static final Pattern TENANT = Pattern.compile("[a-z][a-z0-9]{1,15}");

    private final int shard;
    private final int total;
    private final HttpServer server;
    private final Set<String> tenants = new TreeSet<>(Set.of("btcl"));
    private final Map<String, long[]> next = new HashMap<>();          // "tenant/entity" → {next value}
    private final Map<String, String> typeOf = new HashMap<>();
    final List<String> calls = new CopyOnWriteArrayList<>();          // "GET /api/v2/btcl/next-id/x", …
    volatile int failEverythingWith;                                    // e.g. 500
    volatile String failMintWith;                                       // e.g. "400 Type mismatch", "409 Range exhausted"
    volatile boolean refuseResetAsBackward;
    volatile boolean olderThanTenancy;                                  // the bed's binary today: no /api/v2 road, a bare "404 page not found"

    StubShard(int shard, int total) throws IOException {
        this.shard = shard;
        this.total = total;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    /** The tenants this shard serves (btcl by default). */
    synchronized StubShard serving(String... more) { tenants.addAll(List.of(more)); return this; }

    /** The next value this shard hands out for {@code entity} in {@code tenant}, or null when it does not know it. */
    synchronized Long nextValue(String tenant, String entity) { long[] n = next.get(tenant + "/" + entity); return n == null ? null : n[0]; }

    /** Register an entity in a tenant as if an earlier process had: its next value. */
    synchronized StubShard known(String tenant, String entity, String type, long nextValue) {
        next.put(tenant + "/" + entity, new long[]{nextValue});
        typeOf.put(tenant + "/" + entity, type);
        return this;
    }

    long countOf(String verbAndPrefix) { return calls.stream().filter(c -> c.startsWith(verbAndPrefix)).count(); }

    private synchronized void handle(HttpExchange x) throws IOException {
        URI uri = x.getRequestURI();
        String path = uri.getPath();
        calls.add(x.getRequestMethod() + " " + path);
        if (olderThanTenancy) { plainText(x, 404, "404 page not found\n"); return; }
        if (failEverythingWith > 0) { reply(x, failEverythingWith, Map.of("error", "Generation failed", "message", "scripted")); return; }
        Matcher v2 = V2.matcher(path);
        if (!v2.matches()) {
            if (path.startsWith("/api/")) reply(x, 400, Map.of("error", "Tenant is required", "message", "this installation serves tenants by name: call /api/v2/{tenant}" + path.substring(4)));
            else reply(x, 404, Map.of("error", "Not found", "message", path));
            return;
        }
        String tenant = v2.group(1), road = v2.group(2), entity = v2.group(3);
        if (tenant.isEmpty()) { reply(x, 400, Map.of("error", "Tenant is required", "message", "the tenant comes first: /api/v2/{tenant}/" + road + "/{entity}")); return; }
        if (!TENANT.matcher(tenant).matches()) { reply(x, 400, Map.of("error", "Invalid tenant", "message", "tenant '" + tenant + "' breaks the rule ^[a-z][a-z0-9]{1,15}$")); return; }
        if (!tenants.contains(tenant)) { reply(x, 400, Map.of("error", "Unknown tenant", "message", "tenant '" + tenant + "' is not served by this installation (served tenants: " + String.join(", ", tenants) + ")")); return; }
        Map<String, String> q = query(uri.getRawQuery());
        String key = tenant + "/" + entity;
        switch (road) {
            case "next-id", "next-batch" -> mint(x, tenant, key, entity, q);
            case "status" -> status(x, tenant, key, entity);
            case "init" -> init(x, tenant, key, entity, JSON.readTree(x.getRequestBody()));
            case "reset" -> reset(x, tenant, key, entity, JSON.readTree(x.getRequestBody()));
            default -> reply(x, 404, Map.of("error", "Not found", "message", "no road '" + road + "' under /api/v2/" + tenant + "/", "tenant", tenant));
        }
    }

    private void mint(HttpExchange x, String tenant, String key, String entity, Map<String, String> q) throws IOException {
        if (failMintWith != null) {
            int code = Integer.parseInt(failMintWith.substring(0, 3));
            reply(x, code, Map.of("error", failMintWith.substring(4), "message", "scripted", "tenant", tenant));
            return;
        }
        String type = q.get("dataType");
        String bound = typeOf.putIfAbsent(key, type);
        if (bound != null && !bound.equals(type)) { reply(x, 400, Map.of("error", "Type mismatch", "message", "registered as " + bound, "registeredType", bound, "tenant", tenant)); return; }
        long[] n = next.computeIfAbsent(key, e -> new long[]{shard});
        int count = q.containsKey("batchSize") ? Integer.parseInt(q.get("batchSize")) : 1;
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < count; i++) { long v = n[0]; n[0] += total; values.add("int".equals(type) ? (Object) v : Long.toString(v)); }
        if (q.containsKey("batchSize")) reply(x, 200, Map.of("dataType", type, "entityName", entity, "shard", shard, "tenant", tenant, "values", values));
        else reply(x, 200, Map.of("dataType", type, "entityName", entity, "shard", shard, "tenant", tenant, "value", values.get(0)));
    }

    private void status(HttpExchange x, String tenant, String key, String entity) throws IOException {
        long[] n = next.get(key);
        if (n == null) { reply(x, 404, Map.of("error", "Entity not found", "tenant", tenant)); return; }
        Object nextValue = "int".equals(typeOf.get(key)) ? (Object) n[0] : Long.toString(n[0]);
        reply(x, 200, Map.of("entityName", entity, "dataType", typeOf.get(key), "nextValue", nextValue, "shard", shard, "tenant", tenant));
    }

    private void init(HttpExchange x, String tenant, String key, String entity, JsonNode body) throws IOException {
        if (next.containsKey(key)) { reply(x, 409, Map.of("error", "Entity already exists", "message", entity, "tenant", tenant)); return; }
        long start = snapForward(body.path("startValue").asLong(shard));
        next.put(key, new long[]{start});
        typeOf.put(key, body.path("dataType").asText());
        reply(x, 201, Map.of("entityName", entity, "initialized", true, "actualStartValue", Long.toString(start), "tenant", tenant));
    }

    private void reset(HttpExchange x, String tenant, String key, String entity, JsonNode body) throws IOException {
        long[] n = next.get(key);
        if (n == null) { reply(x, 404, Map.of("error", "Entity not found", "tenant", tenant)); return; }
        long to = snapForward(body.path("value").asLong());
        if (refuseResetAsBackward || to < n[0]) { reply(x, 409, Map.of("error", "Backward reset refused", "message", "next is " + n[0], "tenant", tenant)); return; }
        n[0] = to;
        reply(x, 200, Map.of("entityName", entity, "nextValue", to, "tenant", tenant));
    }

    private long snapForward(long v) { long r = Math.floorMod(v - shard, (long) total); return r == 0 ? v : v + (total - r); }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null) return q;
        for (String kv : raw.split("&")) { int eq = kv.indexOf('='); q.put(kv.substring(0, eq), kv.substring(eq + 1)); }
        return q;
    }

    private static void reply(HttpExchange x, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = x.getResponseBody()) { out.write(bytes); }
    }

    private static void plainText(HttpExchange x, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = x.getResponseBody()) { out.write(bytes); }
    }

    @Override
    public void close() { server.stop(0); }
}
