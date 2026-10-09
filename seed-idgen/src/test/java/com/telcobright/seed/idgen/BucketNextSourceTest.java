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

/** The HTTP source against shards that act like bucket-next: the wire, the failover, the refusals, the counter raised above the ids in use. */
class BucketNextSourceTest {

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

    // ── the wire ─────────────────────────────────────────────────────────────

    @Test
    void anIntArrivesAsANumber_aLongAsAString_aBatchAsValues_allAsTheServicesDecimal() throws Exception {
        IdSource ids = sourceOf(shard(1, 1).url());

        assertThat(ids.mint("order", IdKind.INT, 1)).containsExactly("1");
        assertThat(ids.mint("cdr", IdKind.LONG, 1)).containsExactly("1");
        assertThat(ids.mint("order", IdKind.INT, 3)).containsExactly("2", "3", "4");
    }

    @Test
    void theShardsAreAskedInTurn_eachMintsItsOwnResidue() throws Exception {
        IdSource ids = sourceOf(shard(1, 2).url(), shard(2, 2).url());

        List<String> minted = new ArrayList<>();
        for (int i = 0; i < 4; i++) minted.addAll(ids.mint("x", IdKind.INT, 1));

        assertThat(minted).containsExactly("1", "2", "3", "4");
    }

    // ── a shard that cannot serve: the next one ─────────────────────────────

    @Test
    void aShardFailingOnItsSide_theNextShardMints() throws Exception {
        StubShard a = shard(1, 2), b = shard(2, 2);
        a.failEverythingWith = 500;

        assertThat(sourceOf(a.url(), b.url()).mint("x", IdKind.INT, 1)).containsExactly("2");
        assertThat(a.countOf("GET /api/next-id")).isEqualTo(1);
        assertThat(b.countOf("GET /api/next-id")).isEqualTo(1);
    }

    @Test
    void aShardNobodyListensOn_theNextShardMints() throws Exception {
        StubShard b = shard(2, 2);

        assertThat(sourceOf(nobodyListens(), b.url()).mint("x", IdKind.INT, 1)).containsExactly("2");
    }

    @Test
    void aShardOutOfValues_theNextShardMints() throws Exception {
        StubShard a = shard(1, 2), b = shard(2, 2);
        a.failMintWith = "409 Range exhausted";

        assertThat(sourceOf(a.url(), b.url()).mint("x", IdKind.INT, 1)).containsExactly("2");
    }

    @Test
    void noShardServes_nothingIsMinted_everyShardIsNamed() throws Exception {
        StubShard a = shard(1, 2);
        a.failEverythingWith = 503;
        String down = nobodyListens();

        assertThatThrownBy(() -> sourceOf(a.url(), down).mint("x", IdKind.INT, 1))
            .isInstanceOf(IdServiceUnavailable.class)
            .satisfies(e -> assertThat(((IdServiceUnavailable) e).failures()).hasSize(2))
            .hasMessageContaining(a.url()).hasMessageContaining(down);
    }

    // ── a request the service refuses: refused everywhere ───────────────────

    @Test
    void aTypeMismatch_isRefused_andNoOtherShardIsAsked() throws Exception {
        StubShard a = shard(1, 2), b = shard(2, 2);
        a.known("order", "long", 1);

        assertThatThrownBy(() -> sourceOf(a.url(), b.url()).mint("order", IdKind.INT, 1))
            .isInstanceOf(IdRefused.class)
            .satisfies(e -> assertThat(((IdRefused) e).code()).isEqualTo("Type mismatch"));
        assertThat(b.calls).isEmpty();
    }

    @Test
    void badRequestsNeverLeaveTheProcess() throws Exception {
        StubShard a = shard(1, 1);
        IdSource ids = sourceOf(a.url());

        assertThatThrownBy(() -> ids.mint("a b", IdKind.INT, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ids.mint("x", IdKind.INT, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ids.mint("x", IdKind.INT, 10_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ids.mintAbove("x", IdKind.UUID12, 1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThat(a.calls).isEmpty();
    }

    // ── the counter raised above the ids in use ────────────────────────────

    @Test
    void aShardThatDoesNotKnowTheEntity_isRegisteredAboveTheIdsInUse_thenMints() throws Exception {
        StubShard a = shard(1, 2);

        assertThat(sourceOf(a.url()).mintAbove("partner.btcl", IdKind.INT, 1, 5000)).containsExactly("5001");
        assertThat(a.calls).containsExactly("GET /api/status/partner.btcl", "POST /api/init/partner.btcl", "GET /api/next-id/partner.btcl");
    }

    @Test
    void aShardWhoseCounterWouldHandOutAnIdInUse_isMovedForwardFirst() throws Exception {
        StubShard b = shard(2, 2).known("partner.btcl", "int", 4);

        assertThat(sourceOf(b.url()).mintAbove("partner.btcl", IdKind.INT, 1, 5000)).containsExactly("5002");
        assertThat(b.countOf("PUT /api/reset/partner.btcl")).isEqualTo(1);
    }

    @Test
    void aShardAlreadyPastTheIdsInUse_isNotMoved() throws Exception {
        StubShard a = shard(1, 1).known("partner.btcl", "int", 9000);

        assertThat(sourceOf(a.url()).mintAbove("partner.btcl", IdKind.INT, 1, 5000)).containsExactly("9000");
        assertThat(a.countOf("PUT")).isZero();
    }

    @Test
    void aMoveTheServiceCallsBackward_meansAnotherProcessMovedItPast_theShardMints() throws Exception {
        StubShard a = shard(1, 1).known("partner.btcl", "int", 4);
        a.refuseResetAsBackward = true;

        assertThat(sourceOf(a.url()).mintAbove("partner.btcl", IdKind.INT, 1, 5000)).hasSize(1);
        assertThat(a.countOf("PUT")).isEqualTo(1);
    }

    @Test
    void theShardThatMints_isTheOneRaised_evenAfterAFailover() throws Exception {
        StubShard a = shard(1, 2), b = shard(2, 2);
        a.failEverythingWith = 500;

        assertThat(sourceOf(a.url(), b.url()).mintAbove("partner.btcl", IdKind.INT, 1, 5000)).containsExactly("5002");
        assertThat(b.nextValue("partner.btcl")).isEqualTo(5004L);
    }

    @Test
    void nothingAboveTheLargestInt_isRefusedBeforeAnyCall() throws Exception {
        StubShard a = shard(1, 1);

        assertThatThrownBy(() -> sourceOf(a.url()).mintAbove("partner.btcl", IdKind.INT, 1, Integer.MAX_VALUE)).isInstanceOf(IdRefused.class);
        assertThat(a.calls).hasSize(0);
    }
}
