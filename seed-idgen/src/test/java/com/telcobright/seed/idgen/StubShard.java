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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One bucket-next shard in the test, on 127.0.0.1: the five roads the client uses with the service's shard math (shard k of N hands
 * out k, k+N, …), its type binding, its init snapped forward, its forward-only reset — and the faults a test asks for.
 */
final class StubShard implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int shard;
    private final int total;
    private final HttpServer server;
    private final Map<String, long[]> next = new HashMap<>();          // entity → {next value}
    private final Map<String, String> typeOf = new HashMap<>();
    final List<String> calls = new CopyOnWriteArrayList<>();          // "GET /api/next-id/x", …
    volatile int failEverythingWith;                                    // e.g. 500
    volatile String failMintWith;                                       // e.g. "400 Type mismatch", "409 Range exhausted"
    volatile boolean refuseResetAsBackward;

    StubShard(int shard, int total) throws IOException {
        this.shard = shard;
        this.total = total;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    /** The next value this shard hands out for {@code entity}, or null when it does not know it. */
    synchronized Long nextValue(String entity) { long[] n = next.get(entity); return n == null ? null : n[0]; }

    /** Register an entity as if an earlier process had: its next value. */
    synchronized StubShard known(String entity, String type, long nextValue) { next.put(entity, new long[]{nextValue}); typeOf.put(entity, type); return this; }

    long countOf(String verbAndPrefix) { return calls.stream().filter(c -> c.startsWith(verbAndPrefix)).count(); }

    private synchronized void handle(HttpExchange x) throws IOException {
        URI uri = x.getRequestURI();
        String path = uri.getPath();
        calls.add(x.getRequestMethod() + " " + path);
        if (failEverythingWith > 0) { reply(x, failEverythingWith, Map.of("error", "Generation failed", "message", "scripted")); return; }
        Map<String, String> q = query(uri.getRawQuery());
        String entity = path.substring(path.lastIndexOf('/') + 1);
        if (path.startsWith("/api/next-id/") || path.startsWith("/api/next-batch/")) { mint(x, entity, q); return; }
        if (path.startsWith("/api/status/")) { status(x, entity); return; }
        if (path.startsWith("/api/init/")) { init(x, entity, JSON.readTree(x.getRequestBody())); return; }
        if (path.startsWith("/api/reset/")) { reset(x, entity, JSON.readTree(x.getRequestBody())); return; }
        reply(x, 404, Map.of("error", "Not found", "message", path));
    }

    private void mint(HttpExchange x, String entity, Map<String, String> q) throws IOException {
        if (failMintWith != null) {
            int code = Integer.parseInt(failMintWith.substring(0, 3));
            reply(x, code, Map.of("error", failMintWith.substring(4), "message", "scripted"));
            return;
        }
        String type = q.get("dataType");
        String bound = typeOf.putIfAbsent(entity, type);
        if (bound != null && !bound.equals(type)) { reply(x, 400, Map.of("error", "Type mismatch", "message", "registered as " + bound, "registeredType", bound)); return; }
        long[] n = next.computeIfAbsent(entity, e -> new long[]{shard});
        int count = q.containsKey("batchSize") ? Integer.parseInt(q.get("batchSize")) : 1;
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < count; i++) { long v = n[0]; n[0] += total; values.add("int".equals(type) ? (Object) v : Long.toString(v)); }
        if (q.containsKey("batchSize")) reply(x, 200, Map.of("dataType", type, "entityName", entity, "shard", shard, "values", values));
        else reply(x, 200, Map.of("dataType", type, "entityName", entity, "shard", shard, "value", values.get(0)));
    }

    private void status(HttpExchange x, String entity) throws IOException {
        long[] n = next.get(entity);
        if (n == null) { reply(x, 404, Map.of("error", "Entity not found", "message", entity)); return; }
        Object nextValue = "int".equals(typeOf.get(entity)) ? (Object) n[0] : Long.toString(n[0]);
        reply(x, 200, Map.of("entityName", entity, "dataType", typeOf.get(entity), "nextValue", nextValue, "shard", shard));
    }

    private void init(HttpExchange x, String entity, JsonNode body) throws IOException {
        if (next.containsKey(entity)) { reply(x, 409, Map.of("error", "Entity already exists", "message", entity)); return; }
        long start = snapForward(body.path("startValue").asLong(shard));
        next.put(entity, new long[]{start});
        typeOf.put(entity, body.path("dataType").asText());
        reply(x, 201, Map.of("entityName", entity, "initialized", true, "actualStartValue", Long.toString(start)));
    }

    private void reset(HttpExchange x, String entity, JsonNode body) throws IOException {
        long[] n = next.get(entity);
        if (n == null) { reply(x, 404, Map.of("error", "Entity not found", "message", entity)); return; }
        long to = snapForward(body.path("value").asLong());
        if (refuseResetAsBackward || to < n[0]) { reply(x, 409, Map.of("error", "Backward reset refused", "message", "next is " + n[0])); return; }
        n[0] = to;
        reply(x, 200, Map.of("entityName", entity, "nextValue", to));
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

    @Override
    public void close() { server.stop(0); }
}
