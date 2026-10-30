package com.telcobright.seed.idgen.live;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * REAL tenant-first bucket-next shards started here on 127.0.0.1 ({@link BucketNextBinary}: built from a git archive of the
 * {@code tenant-first} ref into a temp dir), each on its own {@code state_dir} in the test's directory, with a config of the new shape —
 * {@code state_dir}, {@code tenants: [...]}, optionally {@code legacy_tenant} — on a port in the 7000 range (7710–7799, the first free).
 * Every endpoint printed before it is used; every shard stopped by its process at {@link #close()}, never by name.
 *
 * <p>The bed's shape ({@link #bedShaped}: {@code tenants: [btcl]}, {@code legacy_tenant: btcl}, the profile as committed) is one shape;
 * {@link #serving} with no legacy tenant is the shape after the callers moved — the pre-tenant roads then answer 400.
 */
public final class LiveShards implements AutoCloseable {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    private static final int FIRST_PORT = 7710, LAST_PORT = 7799;

    private final Path dir;
    private final int total;
    private final List<String> tenants;
    private final String legacyTenant;
    private final Map<Integer, Process> running = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> portOf = new ConcurrentHashMap<>();

    private LiveShards(Path dir, int total, List<String> tenants, String legacyTenant) {
        this.dir = dir;
        this.total = total;
        this.tenants = List.copyOf(tenants);
        this.legacyTenant = legacyTenant;
    }

    /** A cluster serving {@code tenants}, no {@code legacy_tenant}: every pre-tenant road answers 400 {@code Tenant is required}. */
    public static LiveShards serving(Path dir, int total, String... tenants) { return new LiveShards(dir, total, List.of(tenants), null); }

    /** A cluster serving {@code tenants} with the pre-tenant roads acting on {@code legacyTenant} (one of them). */
    public static LiveShards servingWithLegacy(Path dir, int total, String legacyTenant, String... tenants) { return new LiveShards(dir, total, List.of(tenants), legacyTenant); }

    /** The bed's profile as committed (bucket-next {@code config/tenants/btcl/bed/profile-bed.yml}): {@code tenants: [btcl]}, {@code legacy_tenant: btcl}. */
    public static LiveShards bedShaped(Path dir, int total) { return servingWithLegacy(dir, total, "btcl", "btcl"); }

    public static boolean theBinaryIsHere() { return BucketNextBinary.available(); }

    public static String whyNot() { return BucketNextBinary.whyNot().orElse(""); }

    /** Shard {@code k} of the cluster started (its state made the first time: {@code -init}) and healthy. */
    public void start(int shard, boolean init) throws Exception {
        int port = portOf.computeIfAbsent(shard, k -> freePortInThe7000Range());
        System.out.println("LiveShards: bucket-next shard " + shard + " of " + total + " listens on " + url(shard) + " (tenants " + tenants + (legacyTenant == null ? ", no legacy_tenant)" : ", legacy_tenant " + legacyTenant + ")"));
        Path config = dir.resolve("shard-" + shard + ".yaml");
        Files.writeString(config, configOf(shard, port));
        List<String> command = new ArrayList<>(List.of(BucketNextBinary.path().toString(), "-config", config.toString()));
        if (init) command.add("-init");
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("shard-" + shard + ".log").toFile()).start();
        running.put(shard, p);
        awaitHealthy(shard, p);
    }

    private String configOf(int shard, int port) {
        List<String> lines = new ArrayList<>(List.of(
            "shard_id: " + shard, "total_shards: " + total, "listen_port: " + port, "listen_address: 127.0.0.1",
            "state_dir: " + dir.resolve("shard-" + shard), "tenants: [" + String.join(", ", tenants) + "]"));
        if (legacyTenant != null) lines.add("legacy_tenant: " + legacyTenant);
        lines.add("segment_size: 10");
        lines.add("");
        return String.join("\n", lines);
    }

    /** A crash: the shard killed, no clean shutdown. */
    public void kill(int shard) throws InterruptedException {
        Process p = running.remove(shard);
        if (p != null) p.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
    }

    /** Every shard's base URL, comma-separated — the one config value an application passes to {@code BucketNext.builder().shards(…)}. */
    public String everyShard() {
        List<String> urls = new ArrayList<>();
        for (int k = 1; k <= total; k++) urls.add(url(k));
        return String.join(",", urls);
    }

    public String url(int shard) { return "http://127.0.0.1:" + portOf.computeIfAbsent(shard, k -> freePortInThe7000Range()); }

    /** A plain GET on one shard, for a test's own look at the service (status, shard-info, a pre-tenant road): status and body. */
    public HttpResponse<String> get(int shard, String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url(shard) + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() throws InterruptedException {
        for (Integer shard : List.copyOf(running.keySet())) kill(shard);
    }

    private void awaitHealthy(int shard, Process p) throws Exception {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            if (!p.isAlive()) throw new IllegalStateException("shard " + shard + " stopped: " + Files.readString(dir.resolve("shard-" + shard + ".log")));
            try {
                if (get(shard, "/health").statusCode() == 200) return;
            } catch (IOException notYet) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("shard " + shard + " did not answer /health within 5 s");
    }

    /** The first free port of 7710..7799 on 127.0.0.1 (the house rule: tests and containers in the 7000 range); taken ports are skipped. */
    private int freePortInThe7000Range() {
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            if (portOf.containsValue(port)) continue;
            try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) { return s.getLocalPort(); } catch (IOException taken) { /* the next */ }
        }
        throw new IllegalStateException("no free port in " + FIRST_PORT + ".." + LAST_PORT + " on 127.0.0.1");
    }
}
