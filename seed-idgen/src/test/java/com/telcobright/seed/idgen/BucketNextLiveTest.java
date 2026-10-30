package com.telcobright.seed.idgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.seed.idgen.api.IdKind;
import com.telcobright.seed.idgen.api.IdRefused;
import com.telcobright.seed.idgen.api.PartnerIds;
import com.telcobright.seed.idgen.dependencies.BucketNext;
import com.telcobright.seed.idgen.live.LiveShards;
import com.telcobright.seed.idgen.spi.IdSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The client against the REAL tenant-first bucket-next (the {@code tenant-first} ref built from a git archive into a temp dir, started
 * here on 127.0.0.1 in the 7000 range with a config of the new shape: {@code state_dir}, {@code tenants}, {@code legacy_tenant} where
 * the bed's profile has it). Skipped when the service cannot be built here ({@code -Dbucketnext.bin=<path>} names a built binary).
 *
 * <p>The bed's binary today (fc779e7, pre-tenant) is NOT a fixture: it has no {@code /api/v2/{tenant}/} road at all (a bare
 * {@code 404 page not found}), so this client cannot run against it — it refuses in words naming the tenant-first service
 * ({@code BucketNextSourceTest.aServiceOlderThanTenancy_…}). The old run shape ({@code state_path}) is gone with it.
 */
class BucketNextLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BTCL = "btcl";

    @TempDir Path dir;
    private LiveShards shards;

    @BeforeEach
    void theServiceCanBeBuiltHere() { assumeTrue(LiveShards.theBinaryIsHere(), LiveShards.whyNot()); }

    @AfterEach
    void stopEveryShard() throws Exception { if (shards != null) shards.close(); }

    private static IdSource clientOf(LiveShards shards) {
        return BucketNext.builder().shards(shards.everyShard()).timeout(Duration.ofSeconds(1)).build();
    }

    // ── 1 · the bed's shape: two shards, legacy_tenant kept, the partner rule, a crash, nothing repeats, the v1 roads never touched ──

    @Test
    void theBedsShape_twoRealShards_manyCreatorsAtOnce_neverTheSameId_aboveTheTree_aShardKilledAndBack_nothingRepeats_noV1Call() throws Exception {
        shards = LiveShards.bedShaped(dir, 2);
        shards.start(1, true);
        shards.start(2, true);
        PartnerIds partners = new PartnerIds(clientOf(shards));

        Set<Integer> minted = mintConcurrently(partners, 400);
        assertThat(minted).hasSize(400).allSatisfy(id -> assertThat(id).isGreaterThan(5000));
        assertThat(minted.stream().map(id -> id % 2).distinct()).as("both shards minted").hasSize(2);

        shards.kill(1);                                                         // a crash: no clean shutdown
        List<Integer> whileOneIsDown = mintOneByOne(partners, 20);
        assertThat(whileOneIsDown).allSatisfy(id -> assertThat(id % 2).as("only shard 2 serves").isZero());
        assertThat(whileOneIsDown).doesNotContainAnyElementsOf(minted);

        shards.start(1, false);                                                 // back, on its own state_dir, no -init
        List<Integer> afterTheCrash = mintOneByOne(partners, 40);
        Set<Integer> all = new HashSet<>(minted);
        all.addAll(whileOneIsDown);
        assertThat(afterTheCrash).doesNotContainAnyElementsOf(all).allSatisfy(id -> assertThat(id).isGreaterThan(5000));
        assertThat(afterTheCrash.stream().filter(id -> id % 2 == 1)).as("shard 1 serves again").isNotEmpty();
        all.addAll(afterTheCrash);
        assertThat(all).hasSize(460);

        JsonNode info = body(shards.get(2, "/shard-info"));
        assertThat(info.path("legacyTenant").asText()).isEqualTo(BTCL);
        assertThat(info.path("v1Calls").asLong()).as("the client never used a pre-tenant road").isZero();
        assertThat(body(shards.get(2, "/api/v2/btcl/status/partner.btcl")).path("tenant").asText()).isEqualTo(BTCL);
    }

    // ── 2 · after the callers moved: no legacy_tenant — every road of the client on v2; the pre-tenant roads answer 400 ──

    @Test
    void withoutLegacyTenant_mintAndMintAbove_everyRoadOnV2_theRegistrationOnA404_theMoveForward_thePreTenantRoadsRefuse() throws Exception {
        shards = LiveShards.serving(dir, 1, BTCL);
        shards.start(1, true);
        IdSource ids = clientOf(shards);

        assertThat(ids.mint(BTCL, "order", IdKind.INT, 1)).as("next-id").containsExactly("1");
        assertThat(ids.mint(BTCL, "order", IdKind.INT, 3)).as("next-batch").containsExactly("2", "3", "4");
        assertThat(ids.mint(BTCL, "session", IdKind.UUID12, 2)).as("a non-counter kind").hasSize(2).allSatisfy(v -> assertThat(v).hasSize(12));

        assertThat(shards.get(1, "/api/v2/btcl/status/partner.btcl").statusCode()).as("unknown to the shard yet").isEqualTo(404);
        assertThat(ids.mintAbove(BTCL, "partner.btcl", IdKind.INT, 1, 5000)).as("registered on the 404, started above").containsExactly("5001");
        assertThat(body(shards.get(1, "/api/v2/btcl/status/partner.btcl")).path("nextValue").asLong()).isEqualTo(5002);

        assertThat(ids.mintAbove(BTCL, "order", IdKind.INT, 1, 9000)).as("a known counter moved forward past the ids in use").containsExactly("9001");
        assertThat(ids.mintAbove(BTCL, "order", IdKind.INT, 2, 100)).as("already past: not moved back").containsExactly("9002", "9003");

        HttpResponse<String> v1 = shards.get(1, "/api/next-id/order?dataType=int");
        assertThat(v1.statusCode()).as("the pre-tenant road without legacy_tenant").isEqualTo(400);
        assertThat(body(v1).path("error").asText()).isEqualTo("Tenant is required");
        JsonNode legacy = body(shards.get(1, "/shard-info")).path("legacyTenant");
        assertThat(legacy.isMissingNode() || legacy.isNull() || legacy.asText().isEmpty()).as("no legacy_tenant on /shard-info: " + legacy).isTrue();
    }

    // ── 3 · the same entity name in a second declared tenant = a separate counter ──

    @Test
    void theSameEntityInASecondDeclaredTenant_isASeparateCounter() throws Exception {
        shards = LiveShards.serving(dir, 1, BTCL, "acme");
        shards.start(1, true);
        IdSource ids = clientOf(shards);

        assertThat(ids.mint(BTCL, "order", IdKind.INT, 3)).containsExactly("1", "2", "3");
        assertThat(ids.mint("acme", "order", IdKind.INT, 2)).as("acme counts from 1 on its own").containsExactly("1", "2");
        assertThat(ids.mintAbove("acme", "order", IdKind.INT, 1, 700)).containsExactly("701");
        assertThat(ids.mint(BTCL, "order", IdKind.INT, 1)).as("btcl's counter untouched by acme's raise").containsExactly("4");
        assertThat(ids.mint(BTCL, "cdr", IdKind.LONG, 1)).containsExactly("1");
        assertThat(ids.mint("acme", "cdr", IdKind.INT, 1)).as("a kind is bound per (tenant, entity), not per name").containsExactly("1");

        assertThat(body(shards.get(1, "/api/v2/btcl/status/order")).path("nextValue").asLong()).isEqualTo(5);
        assertThat(body(shards.get(1, "/api/v2/acme/status/order")).path("nextValue").asLong()).isEqualTo(702);
        assertThat(body(shards.get(1, "/shard-info")).path("tenants")).extracting(JsonNode::asText).containsExactly("acme", BTCL);
    }

    // ── 4 · a tenant the installation does not serve: refused in the service's words, nothing registered ──

    @Test
    void anUnknownTenant_isRefusedInTheServicesWords_nothingRegistered_aBlankTenantNeverLeavesTheProcess() throws Exception {
        shards = LiveShards.serving(dir, 1, BTCL);
        shards.start(1, true);
        IdSource ids = clientOf(shards);

        assertThatThrownBy(() -> ids.mint("nobody", "order", IdKind.INT, 1))
            .isInstanceOf(IdRefused.class)
            .satisfies(e -> assertThat(((IdRefused) e).status()).isEqualTo(400))
            .satisfies(e -> assertThat(((IdRefused) e).code()).isEqualTo("Unknown tenant"))
            .hasMessageContaining("nobody/order").hasMessageContaining("tenant 'nobody' is not served by this installation (served tenants: btcl)");
        assertThatThrownBy(() -> ids.mintAbove("nobody", "partner.btcl", IdKind.INT, 1, 5000))
            .as("400, never 404: an unknown tenant is not an unregistered entity").isInstanceOf(IdRefused.class).hasMessageContaining("Unknown tenant");
        assertThatThrownBy(() -> ids.mint("BTCL", "order", IdKind.INT, 1)).as("ill-formed: refused here, before any call")
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant-master's tenant id");
        assertThatThrownBy(() -> ids.mint(" ", "order", IdKind.INT, 1)).isInstanceOf(IllegalArgumentException.class);

        assertThat(body(shards.get(1, "/api/v2/btcl/list")).path("entities")).as("nothing registered anywhere").isEmpty();
        assertThat(body(shards.get(1, "/shard-info")).path("v1Calls").asLong()).isZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static JsonNode body(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }

    private static Set<Integer> mintConcurrently(PartnerIds partners, int count) throws InterruptedException {
        Set<Integer> minted = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        IntStream.range(0, count).forEach(i -> pool.submit(() -> minted.add(partners.next(BTCL, BTCL, 5000))));
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return minted;
    }

    private static List<Integer> mintOneByOne(PartnerIds partners, int count) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(partners.next(BTCL, BTCL, 5000));
        return out;
    }
}
