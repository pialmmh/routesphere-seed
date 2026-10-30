package com.telcobright.seed.prepaid.admin.lab;

import com.telcobright.seed.idgen.dependencies.BucketNext;
import com.telcobright.seed.idgen.live.LiveShards;
import com.telcobright.seed.sessionflow.spi.LedgerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L2c item 4 against TWO REAL bucket-next shards on 127.0.0.1 — the tenant-first service built from a git archive by seed-idgen's live
 * fixture, in the bed's shape ({@code tenants: [btcl]}, {@code legacy_tenant: btcl}); skipped when it cannot be built here. The
 * application's {@code IdSource} is {@code BucketNext.builder().shards(<every shard's URL, comma-separated>)}, every mint under the root's
 * tenant {@code btcl} (ARCH-0100); 60 purchases at once over the three tiers
 * (30, then shard 1 killed, then 30 more) — every purchase made, every purchase id and every account id unique (shard 1 hands out odd
 * ids, shard 2 even: after the kill only even ones), each above the highest its table held (purchases 300, accounts 500); then the
 * database = the cache in every tier. H2 in
 * MySQL mode, in process (the engines' scenario proves the rest on MySQL and PostgreSQL).
 */
class PurchasesOverTwoLiveShardsTest {

    @TempDir Path dir;
    private LiveShards shards;
    private PurchaseLab books;
    private final AtomicInteger packages = new AtomicInteger(10);

    @BeforeEach
    void theServiceCanBeBuiltHere() { assumeTrue(LiveShards.theBinaryIsHere(), LiveShards.whyNot()); }

    @AfterEach
    void nothingIsLeftRunning() throws Exception {
        if (books != null) books.close();
        if (shards != null) shards.close();
    }

    @Test
    void manyPurchasesAtOnceOverThreeTiers_aShardKilledInTheMiddle_everyIdUnique_theDatabaseIsTheCache() throws Exception {
        shards = LiveShards.bedShaped(dir, 2);
        shards.start(1, true);
        shards.start(2, true);
        books = PurchaseLab.over(new H2PurchaseLab(), BucketNext.builder().shards(shards.everyShard()).timeout(Duration.ofSeconds(1)).build());
        books.layDownTheOutsideWritersRows();
        books.start();

        List<LedgerPort.Purchased> before = purchaseConcurrently(30);
        shards.kill(1);
        List<LedgerPort.Purchased> after = purchaseConcurrently(30);

        List<Long> purchaseIds = new ArrayList<>(), accountIds = new ArrayList<>();
        for (List<LedgerPort.Purchased> phase : List.of(before, after)) {
            for (LedgerPort.Purchased p : phase) { purchaseIds.add(p.purchaseId()); accountIds.addAll(p.accounts()); }
        }
        assertThat(purchaseIds).as("60 purchases, no id twice, above every purchase held (300)").hasSize(60).doesNotHaveDuplicates().allMatch(id -> id > 300);
        assertThat(accountIds).as("120 accounts, no id twice, above every account held (500)").hasSize(120).doesNotHaveDuplicates().allMatch(id -> id > 500);
        assertThat(before.stream().flatMap(p -> p.accounts().stream()).map(id -> id % 2).distinct()).as("both shards minted before the kill").hasSize(2);
        assertThat(after).as("only shard 2 (even ids) after the kill").allMatch(p -> p.purchaseId() % 2 == 0 && p.accounts().stream().allMatch(id -> id % 2 == 0));
        for (int t = 0; t < 3; t++) books.awaitTheDatabaseEqualsTheCache(books.tier(t));
    }

    /** {@code count} purchases from six threads, spread over the three tiers (each its own package); every one must be made. */
    private List<LedgerPort.Purchased> purchaseConcurrently(int count) throws InterruptedException {
        Queue<LedgerPort.Purchased> made = new ConcurrentLinkedQueue<>();
        Queue<String> failed = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        for (int i = 0; i < count; i++) {
            int tier = i % 3, buyer = tier == 0 ? 700 : tier == 1 ? PurchaseLab.CLIENT : 702, pkg = packages.getAndIncrement();
            pool.submit(() -> {
                try {
                    made.add(books.admin().purchase(books.tier(0), books.tier(tier), buyer, PurchaseLab.offer(pkg), "live-" + pkg));
                } catch (RuntimeException e) {
                    failed.add("package " + pkg + ": " + e);
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(failed).as("every purchase made").isEmpty();
        return List.copyOf(made);
    }
}
