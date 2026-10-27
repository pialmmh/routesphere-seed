package com.telcobright.seed.prepaid.admin.lab;

import java.io.IOException;
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
 * L2c · REAL bucket-next shards started here on 127.0.0.1 (seed-idgen's {@code BucketNextLiveTest} pattern: the binary of
 * git@github.com:pialmmh/bucket-next, {@code make build}; {@code -Dbucketnext.bin=<path>} to name another), each on its own state file
 * in the test's directory; every endpoint printed before it is used; every shard stopped at {@link #close()}.
 */
public final class LiveShards implements AutoCloseable {

    public static final Path BIN = Path.of(System.getProperty("bucketnext.bin",
        System.getProperty("user.home") + "/telcobright-projects/bucket-next/bin/bucket-next"));
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();

    private final Path dir;
    private final int total;
    private final Map<Integer, Process> running = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> portOf = new ConcurrentHashMap<>();

    public LiveShards(Path dir, int total) {
        this.dir = dir;
        this.total = total;
    }

    public static boolean theBinaryIsHere() { return Files.isExecutable(BIN); }

    /** Shard {@code k} of the cluster started (its state file made the first time) and healthy. */
    public void start(int shard, boolean init) throws Exception {
        int port = portOf.computeIfAbsent(shard, k -> freePort());
        System.out.println("LiveShards: bucket-next shard " + shard + " of " + total + " listens on " + url(shard));
        Path config = dir.resolve("shard-" + shard + ".yaml");
        Files.writeString(config, String.join("\n", "shard_id: " + shard, "total_shards: " + total, "listen_port: " + port,
            "listen_address: 127.0.0.1", "state_path: " + dir.resolve("shard-" + shard + "-state.json"), "segment_size: 10", ""));
        List<String> command = new ArrayList<>(List.of(BIN.toString(), "-config", config.toString()));
        if (init) command.add("-init");
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("shard-" + shard + ".log").toFile()).start();
        running.put(shard, p);
        awaitHealthy(shard, p);
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

    public String url(int shard) { return "http://127.0.0.1:" + portOf.computeIfAbsent(shard, k -> freePort()); }

    @Override
    public void close() throws InterruptedException {
        for (Integer shard : List.copyOf(running.keySet())) kill(shard);
    }

    private void awaitHealthy(int shard, Process p) throws Exception {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            if (!p.isAlive()) throw new IllegalStateException("shard " + shard + " stopped: " + Files.readString(dir.resolve("shard-" + shard + ".log")));
            try {
                if (HTTP.send(HttpRequest.newBuilder(URI.create(url(shard) + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200) return;
            } catch (IOException notYet) {
                Thread.sleep(50);
            }
        }
        throw new IllegalStateException("shard " + shard + " did not answer /health within 5 s");
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { return s.getLocalPort(); } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
