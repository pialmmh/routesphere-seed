package com.telcobright.seed.idgen;

import com.telcobright.seed.idgen.api.IdKind;
import com.telcobright.seed.idgen.api.IdRefused;
import com.telcobright.seed.idgen.api.IdServiceUnavailable;
import com.telcobright.seed.idgen.dependencies.BucketNext;
import com.telcobright.seed.idgen.spi.IdSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP source against shards that act like the tenant-first bucket-next: every road {@code /api/v2/{tenant}/…}, the wire, the
 * failover, the refusals (the tenant's among them), the counter raised above the ids in use — per tenant.
 */
class BucketNextSourceTest {

    private static final String BTCL = "btcl";

    private final List<StubShard> shards = new ArrayList<>();

    @AfterEach
    void stop() { shards.forEach(StubShard::close); }

    private StubShard shard(int k, int total) throws Exception {
        StubShard s = new StubShard(k, total);
        shards.add(s);
        return s;
    }

    private static IdSource sourceOf(String... urls) {
        return BucketNext.builder().shards(String.join(",", urls)).timeout(Duration.ofMillis(800)).build();
    }

    private static String nobodyListens() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return "http://127.0.0.1:" + s.getLocalPort(); }
    }

    // ── the wire: every road tenant-first ────────────────────────────────────

    @Test
    void anIntArrivesAsANumber_aLongAsAString_aBatchAsValues_allAsTheServicesDecimal() throws Exception {
        StubShard a = shard(1, 1);
        IdSource ids = sourceOf(a.url());

        assertThat(ids.mint(BTCL, "order", IdKind.INT, 1)).containsExactly("1");
        assertThat(ids.mint(BTCL, "cdr", IdKind.LONG, 1)).containsExactly("1");
        assertThat(ids.mint(BTCL, "order", IdKind.INT, 3)).containsExactly("2", "3", "4");
        assertThat(a.calls).as("every road names the tenant first")
            .containsExactly("GET /api/v2/btcl/next-id/order", "GET /api/v2/btcl/next-id/cdr", "GET /api/v2/btcl/next-batch/order");
    }

    @Test
    void theShardsAreAskedInTurn_eachMintsItsOwnResidue() throws Exception {
        IdSource ids = sourceOf(shard(1, 2).url(), shard(2, 2).url());

        List<String> minted = new ArrayList<>();
        for (int i = 0; i < 4; i++) minted.addAll(ids.mint(BTCL, "x", IdKind.INT, 1));

        assertThat(minted).containsExactly("1", "2", "3", "4");
    }

    @Test
    void theSameEntityInTwoTenants_isTwoCounters() throws Exception {
        StubShard a = shard(1, 1).serving("acme");
        IdSource ids = sourceOf(a.url());

        assertThat(ids.mint(BTCL, "order", IdKind.INT, 2)).containsExactly("1", "2");
        assertThat(ids.mint("acme", "order", IdKind.INT, 1)).containsExactly("1");
        assertThat(ids.mintAbove("acme", "order", IdKind.INT, 1, 500)).containsExactly("501");
        assertThat(ids.mint(BTCL, "order", IdKind.INT, 1)).as("btcl's counter untouched by acme's raise").containsExactly("3");
    }

    // ── the tenant: the caller's word, refused in words ─────────────────────

    @Test
    void aBlankOrIllFormedTenant_isRefusedBeforeAnyCall() throws Exception {
        StubShard a = shard(1, 1);
        IdSource ids = sourceOf(a.url());

        for (String bad : new String[]{null, "", " ", "BTCL", "bt_cl", "b", "1btcl", "abcdefghijklmnopq"}) {
            assertThatThrownBy(() -> ids.mint(bad, "x", IdKind.INT, 1)).as("mint '" + bad + "'")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant-master's tenant id").hasMessageContaining("no default");
            assertThatThrownBy(() -> ids.mintAbove(bad, "x", IdKind.INT, 1, 10)).as("mintAbove '" + bad + "'").isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(a.calls).as("nothing left the process").isEmpty();
    }

    @Test
    void aTenantTheServiceDoesNotServe_isRefusedInItsWords_andNoOtherShardIsAsked() throws Exception {
        StubShard a = shard(1, 2), b = shard(2, 2);
        IdSource ids = sourceOf(a.url(), b.url());

        assertThatThrownBy(() -> ids.mint("nobody", "x", IdKind.INT, 1))
            .isInstanceOf(IdRefused.class)
            .satisfies(e -> assertThat(((IdRefused) e).code()).isEqualTo("Unknown tenant"))
            .satisfies(e -> assertThat(((IdRefused) e).status()).isEqualTo(400))
            .hasMessageContaining("nobody/x").hasMessageContaining("tenant 'nobody' is not served by this installation");
        assertThatThrownBy(() -> ids.mintAbove("nobody", "x", IdKind.INT, 1, 10))
            .isInstanceOf(IdRefused.class).hasMessageContaining("Unknown tenant").hasMessageContaining("nobody/x status");
        assertThat(a.calls.size() + b.calls.size()).as("one call each: refused everywhere, no failover").isEqualTo(2);
        assertThat(a.countOf("POST") + b.countOf("POST")).as("an unknown tenant never looks like an unregistered entity: nothing registered").isZero();
    }

    @Test
    void aServiceOlderThanTenancy_hasNoV2Road_theClientRefusesNamingIt_nothingMinted() throws Exception {
        StubShard a = shard(1, 1);
        a.olderThanTenancy = true;
        IdSource ids = sourceOf(a.url());

        assertThatThrownBy(() -> ids.mint(BTCL, "x", IdKind.INT, 1))
            .isInstanceOf(IdRefused.class)
            .satisfies(e -> assertThat(((IdRefused) e).code()).isEqualTo("No v2 road"))
            .hasMessageContaining("404 page not found").hasMessageContaining("/api/v2/{tenant}/").hasMessageContaining("tenant-first");
        assertThatThrownBy(() -> ids.mintAbove(BTCL, "partner.btcl", IdKind.INT, 1, 5000))
            .as("a bare 404 from status is not 'Entity not found': no registration is tried").isInstanceOf(IdRefused.class).hasMessageContaining("No v2 road");
        assertThat(a.countOf("POST")).isZero();
    }

    // ── a shard that cannot serve: the next one ─────────────────────────────

    @Test
    void aShardFailingOnItsSide_theNextShardMints() throws Exception {
        StubShard a = shard(1, 2), b = shard(2, 2);
        a.failEverythingWith = 500;

        assertThat(sourceOf(a.url(), b.url()).mint(BTCL, "x", IdKind.INT, 1)).containsExactly("2");
        assertThat(a.countOf("GET /api/v2/btcl/next-id")).isEqualTo(1);
        assertThat(b.countOf("GET /api/v2/btcl/next-id")).isEqualTo(1);
    }

    @Test
    void aShardNobodyListensOn_theNextShardMints() throws Exception {
        StubShard b = shard(2, 2);

        assertThat(sourceOf(nobodyListens(), b.url()).mint(BTCL, "x", IdKind.INT, 1)).containsExactly("2");
    }

    @Test
    void aShardOutOfValues_theNextShardMints() throws Exception {
        StubShard a = shard(1, 2), b = shard(2, 2);
        a.failMintWith = "409 Range exhausted";

        assertThat(sourceOf(a.url(), b.url()).mint(BTCL, "x", IdKind.INT, 1)).containsExactly("2");
    }

    @Test
    void noShardServes_nothingIsMinted_everyShardIsNamed() throws Exception {
        StubShard a = shard(1, 2);
        a.failEverythingWith = 503;
        String down = nobodyListens();

        assertThatThrownBy(() -> sourceOf(a.url(), down).mint(BTCL, "x", IdKind.INT, 1))
            .isInstanceOf(IdServiceUnavailable.class)
            .satisfies(e -> assertThat(((IdServiceUnavailable) e).failures()).hasSize(2))
            .hasMessageContaining("btcl/x").hasMessageContaining(a.url()).hasMessageContaining(down);
    }

    // ── a request the service refuses: refused everywhere ───────────────────

    @Test
    void aTypeMismatch_isRefused_andNoOtherShardIsAsked() throws Exception {
        StubShard a = shard(1, 2), b = shard(2, 2);
        a.known(BTCL, "order", "long", 1);

        assertThatThrownBy(() -> sourceOf(a.url(), b.url()).mint(BTCL, "order", IdKind.INT, 1))
            .isInstanceOf(IdRefused.class)
            .satisfies(e -> assertThat(((IdRefused) e).code()).isEqualTo("Type mismatch"));
        assertThat(b.calls).isEmpty();
    }

    @Test
    void badRequestsNeverLeaveTheProcess() throws Exception {
        StubShard a = shard(1, 1);
        IdSource ids = sourceOf(a.url());

        assertThatThrownBy(() -> ids.mint(BTCL, "a b", IdKind.INT, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ids.mint(BTCL, "x", IdKind.INT, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ids.mint(BTCL, "x", IdKind.INT, 10_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ids.mintAbove(BTCL, "x", IdKind.UUID12, 1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThat(a.calls).isEmpty();
    }

    // ── the counter raised above the ids in use, in its tenant ─────────────

    @Test
    void aShardThatDoesNotKnowTheEntity_isRegisteredAboveTheIdsInUse_thenMints() throws Exception {
        StubShard a = shard(1, 2);

        assertThat(sourceOf(a.url()).mintAbove(BTCL, "partner.btcl", IdKind.INT, 1, 5000)).containsExactly("5001");
        assertThat(a.calls).containsExactly("GET /api/v2/btcl/status/partner.btcl", "POST /api/v2/btcl/init/partner.btcl", "GET /api/v2/btcl/next-id/partner.btcl");
    }

    @Test
    void aShardWhoseCounterWouldHandOutAnIdInUse_isMovedForwardFirst() throws Exception {
        StubShard b = shard(2, 2).known(BTCL, "partner.btcl", "int", 4);

        assertThat(sourceOf(b.url()).mintAbove(BTCL, "partner.btcl", IdKind.INT, 1, 5000)).containsExactly("5002");
        assertThat(b.countOf("PUT /api/v2/btcl/reset/partner.btcl")).isEqualTo(1);
    }

    @Test
    void aShardAlreadyPastTheIdsInUse_isNotMoved() throws Exception {
        StubShard a = shard(1, 1).known(BTCL, "partner.btcl", "int", 9000);

        assertThat(sourceOf(a.url()).mintAbove(BTCL, "partner.btcl", IdKind.INT, 1, 5000)).containsExactly("9000");
        assertThat(a.countOf("PUT")).isZero();
    }

    @Test
    void aMoveTheServiceCallsBackward_meansAnotherProcessMovedItPast_theShardMints() throws Exception {
        StubShard a = shard(1, 1).known(BTCL, "partner.btcl", "int", 4);
        a.refuseResetAsBackward = true;

        assertThat(sourceOf(a.url()).mintAbove(BTCL, "partner.btcl", IdKind.INT, 1, 5000)).hasSize(1);
        assertThat(a.countOf("PUT")).isEqualTo(1);
    }

    @Test
    void theShardThatMints_isTheOneRaised_evenAfterAFailover() throws Exception {
        StubShard a = shard(1, 2), b = shard(2, 2);
        a.failEverythingWith = 500;

        assertThat(sourceOf(a.url(), b.url()).mintAbove(BTCL, "partner.btcl", IdKind.INT, 1, 5000)).containsExactly("5002");
        assertThat(b.nextValue(BTCL, "partner.btcl")).isEqualTo(5004L);
    }

    @Test
    void nothingAboveTheLargestInt_isRefusedBeforeAnyCall() throws Exception {
        StubShard a = shard(1, 1);

        assertThatThrownBy(() -> sourceOf(a.url()).mintAbove(BTCL, "partner.btcl", IdKind.INT, 1, Integer.MAX_VALUE)).isInstanceOf(IdRefused.class);
        assertThat(a.calls).hasSize(0);
    }
}
