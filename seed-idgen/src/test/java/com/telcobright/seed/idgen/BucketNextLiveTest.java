package com.telcobright.seed.idgen;

import com.telcobright.seed.idgen.api.PartnerIds;
import com.telcobright.seed.idgen.dependencies.BucketNext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The partner rule against TWO REAL bucket-next shards started here on 127.0.0.1 (the binary of git@github.com:pialmmh/bucket-next,
 * {@code make build}; {@code -Dbucketnext.bin=<path>} to name another). Skipped when the binary is not on this machine.
 */
class BucketNextLiveTest {

    private static final Path BIN = Path.of(System.getProperty("bucketnext.bin",
        System.getProperty("user.home") + "/telcobright-projects/bucket-next/bin/bucket-next"));
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();

    @TempDir Path dir;
    private final Map<Integer, Process> running = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> portOf = new ConcurrentHashMap<>();

    @BeforeEach
    void theBinaryIsHere() { assumeTrue(Files.isExecutable(BIN), "no bucket-next binary at " + BIN); }

    @AfterEach
    void stopEveryShard() { running.values().forEach(Process::destroyForcibly); }

    @Test
    void twoRealShards_manyCreatorsAtOnce_neverTheSameId_aboveTheTree_aShardKilledAndBack_nothingRepeats() throws Exception {
        start(1, 2, true);
        start(2, 2, true);
        PartnerIds partners = new PartnerIds(BucketNext.builder().shards(url(1) + "," + url(2)).timeout(Duration.ofSeconds(1)).build());

        Set<Integer> minted = mintConcurrently(partners, 400);
        assertThat(minted).hasSize(400).allSatisfy(id -> assertThat(id).isGreaterThan(5000));
        assertThat(minted.stream().map(id -> id % 2).distinct()).as("both shards minted").hasSize(2);

        running.remove(1).destroyForcibly().waitFor(5, TimeUnit.SECONDS);       // a crash: no clean shutdown
        List<Integer> whileOneIsDown = mintOneByOne(partners, 20);
        assertThat(whileOneIsDown).allSatisfy(id -> assertThat(id % 2).as("only shard 2 serves").isZero());
        assertThat(whileOneIsDown).doesNotContainAnyElementsOf(minted);

        start(1, 2, false);                                                     // back, on its own state file, no -init
        List<Integer> afterTheCrash = mintOneByOne(partners, 40);
        Set<Integer> all = new HashSet<>(minted);
        all.addAll(whileOneIsDown);
        assertThat(afterTheCrash).doesNotContainAnyElementsOf(all).allSatisfy(id -> assertThat(id).isGreaterThan(5000));
        assertThat(afterTheCrash.stream().filter(id -> id % 2 == 1)).as("shard 1 serves again").isNotEmpty();
        all.addAll(afterTheCrash);
        assertThat(all).hasSize(460);
    }

    private static Set<Integer> mintConcurrently(PartnerIds partners, int count) throws InterruptedException {
        Set<Integer> minted = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        IntStream.range(0, count).forEach(i -> pool.submit(() -> minted.add(partners.next("btcl", 5000))));
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return minted;
    }

    private static List<Integer> mintOneByOne(PartnerIds partners, int count) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(partners.next("btcl", 5000));
        return out;
    }

    // ── the shards ────────────────────────────────────────────────────────

    private void start(int shard, int total, boolean init) throws Exception {
        int port = portOf.computeIfAbsent(shard, k -> freePort());
        Path config = dir.resolve("shard-" + shard + ".yaml");
        Files.writeString(config, String.join("\n",
            "shard_id: " + shard, "total_shards: " + total, "listen_port: " + port, "listen_address: 127.0.0.1",
            "state_path: " + dir.resolve("shard-" + shard + "-state.json"), "segment_size: 10", ""));
        List<String> command = new ArrayList<>(List.of(BIN.toString(), "-config", config.toString()));
        if (init) command.add("-init");
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("shard-" + shard + ".log").toFile()).start();
        running.put(shard, p);
        awaitHealthy(shard, p);
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

    private String url(int shard) { return "http://127.0.0.1:" + portOf.get(shard); }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
