package com.telcobright.seed.campaign;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignCounters;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignPolicy;
import com.telcobright.seed.campaign.api.CampaignTask;
import com.telcobright.seed.campaign.api.Creative;
import com.telcobright.seed.campaign.api.MediaKind;
import com.telcobright.seed.campaign.api.Placement;
import com.telcobright.seed.campaign.api.Targeting;
import com.telcobright.seed.campaign.api.TaskCharge;
import com.telcobright.seed.campaign.api.TaskState;
import com.telcobright.seed.campaign.api.TimeBand;
import com.telcobright.seed.campaign.api.ViewRequest;
import com.telcobright.seed.campaign.internal.DefaultCampaignService;
import com.telcobright.seed.campaign.testkit.InMemoryCampaignStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service in memory: rank (runnable → targeted → quota → cap → order), claim, complete/fail with counters, the
 * quota closing the campaign, the store told of everything, and a store failure never deciding a task's life.
 */
class CampaignServiceTest {

    static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    static final Instant NOON = Instant.parse("2026-09-27T06:00:00Z");   // Sunday 12:00 in Dhaka (a weekday)
    static final Creative VIDEO = Creative.stream("v1", MediaKind.VIDEO, "vod-1", 15);
    static final Creative IMAGE = Creative.stream("i1", MediaKind.IMAGE, "vod-2", 0);

    final InMemoryCampaignStore store = new InMemoryCampaignStore();
    final DefaultCampaignService service = new DefaultCampaignService(store, Clock.fixed(NOON, DHAKA), DHAKA);

    static Campaign campaign(int id, int priority, int total, Targeting t, CampaignPolicy p, Creative... creatives) {
        return new Campaign(id, "btcl", "c" + id, CampaignKind.AD, "Running", 700 + id, null, null, null, priority,
            total, 0, 0, 0, 10, p, t, List.of(creatives), Map.of());
    }

    static ViewRequest view(String zone, String site, String device) {
        return ViewRequest.at("btcl", zone, site, "dhaka", "wifi-gw2", device, NOON);
    }

    @Test
    void ranking_puts_the_most_specific_first_then_priority_then_the_least_served() {
        service.reloaded("btcl", List.of(
            campaign(1, 0, 0, Targeting.ANY, CampaignPolicy.ALWAYS, VIDEO),
            campaign(2, 5, 0, Targeting.of("zone", "zone0"), CampaignPolicy.ALWAYS, VIDEO),
            campaign(3, 9, 0, Targeting.of("zone", "zone0").and("site", "moghbazar"), CampaignPolicy.ALWAYS, IMAGE),
            campaign(4, 9, 0, Targeting.of("zone", "zone1"), CampaignPolicy.ALWAYS, VIDEO)));

        List<Placement> ranked = service.rank(view("zone0", "moghbazar", "aa:bb"));

        assertThat(ranked).extracting(p -> p.campaign().id()).containsExactly(3, 2, 1);
        assertThat(ranked.get(0).specificity()).isEqualTo(2);
        assertThat(ranked.get(0).viewSeconds()).as("an image takes the campaign's default seconds").isEqualTo(10);
        assertThat(ranked.get(1).viewSeconds()).as("a clip takes its own length").isEqualTo(15);
        assertThat(service.rank(view("zone1", null, "aa:bb"))).extracting(p -> p.campaign().id()).containsExactly(4, 1);
    }

    @Test
    void a_campaign_outside_its_time_band_or_paused_or_expired_or_empty_is_not_ranked() {
        CampaignPolicy nightOnly = new CampaignPolicy(List.of(TimeBand.allowDaily(LocalTime.of(22, 0), LocalTime.of(23, 59))), null);
        service.reloaded("btcl", List.of(
            campaign(1, 0, 0, Targeting.ANY, nightOnly, VIDEO),
            campaign(2, 0, 0, Targeting.ANY, CampaignPolicy.ALWAYS, VIDEO).withStatus("Paused"),
            new Campaign(3, "btcl", "old", CampaignKind.AD, "Running", 0, NOON.minusSeconds(1), null, null, 0, 0, 0, 0, 0, 10,
                CampaignPolicy.ALWAYS, Targeting.ANY, List.of(VIDEO), Map.of()),
            campaign(4, 0, 0, Targeting.ANY, CampaignPolicy.ALWAYS),
            campaign(5, 0, 0, Targeting.ANY, CampaignPolicy.ALWAYS, VIDEO)));

        assertThat(service.rank(view("zone0", null, null))).extracting(p -> p.campaign().id()).containsExactly(5);
    }

    @Test
    void closed_takesTheRowTheSessionBaseClosed_countersAndStoreAsCompleteAndFailWould() {
        service.reloaded("btcl", List.of(campaign(1, 0, 0, Targeting.ANY, CampaignPolicy.ALWAYS, VIDEO, IMAGE)));
        ViewRequest v = view("zone0", null, "aa:bb");

        Placement first = service.rank(v).get(0);
        Placement second = service.rank(v).get(0);
        assertThat(first.creative().id()).isEqualTo("v1");
        assertThat(second.creative().id()).as("creatives rotate").isEqualTo("i1");

        CampaignTask t1 = service.claim(first, v, "ad-btcl-1", "88017", "wifi-9").orElseThrow();
        CampaignTask t2 = service.claim(second, v, "ad-btcl-2", null, null).orElseThrow();

        TaskCharge charge = new TaskCharge(586L, "BDT", BigDecimal.ZERO, new BigDecimal("0.50"), "R300");
        CampaignTask doneRow = t1.answered(NOON).completed(NOON.plusSeconds(15), 15, charge, "viewed");       // the base's CLOSE_TASK row
        CampaignTask kept = service.closed(doneRow);
        assertThat(kept).isSameAs(doneRow);
        assertThat(counters(1).sent()).isEqualTo(1);
        assertThat(counters(1).pending()).isEqualTo(1);
        assertThat(store.tasks.get("ad-btcl-1").state()).isEqualTo(TaskState.SENT);

        CampaignTask lostRow = t2.failed(NOON.plusSeconds(3), 3, "NOT_SHOWN", charge);                         // failed, and it still cost
        assertThat(service.closed(lostRow).charge()).as("a failed row keeps the cost the base put on it").isEqualTo(charge);
        assertThat(counters(1).failed()).isEqualTo(1);
        assertThat(counters(1).pending()).isZero();
        assertThat(store.tasks.get("ad-btcl-2").state()).isEqualTo(TaskState.FAILED);

        assertThatThrownBy(() -> service.closed(t1)).as("an open row is not a closed one").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claim_complete_and_fail_keep_the_counters_and_tell_the_store() {
        service.reloaded("btcl", List.of(campaign(1, 0, 0, Targeting.ANY, CampaignPolicy.ALWAYS, VIDEO, IMAGE)));
        ViewRequest v = view("zone0", null, "aa:bb");

        Placement first = service.rank(v).get(0);
        Placement second = service.rank(v).get(0);
        assertThat(first.creative().id()).isEqualTo("v1");
        assertThat(second.creative().id()).as("creatives rotate").isEqualTo("i1");

        CampaignTask t1 = service.claim(first, v, "ad-btcl-1", "88017", "wifi-9").orElseThrow();
        CampaignTask t2 = service.claim(second, v, "ad-btcl-2", null, null).orElseThrow();
        assertThat(t1.state()).isEqualTo(TaskState.PROCESSING);
        assertThat(t1.subject()).isEqualTo("88017");
        assertThat(t2.subject()).as("no msisdn: the device").isEqualTo("aa:bb");
        assertThat(t1.zone()).isEqualTo("zone0");
        assertThat(counters(1).pending()).isEqualTo(2);

        t1 = service.answered(t1);
        TaskCharge charge = new TaskCharge(586L, "AD_view", BigDecimal.ONE, BigDecimal.ZERO, "zone=zone0");
        CampaignTask done = service.complete(t1, 15, charge, "viewed");
        CampaignTask lost = service.fail(t2, 3, "abandoned:page-left");

        assertThat(done.state()).isEqualTo(TaskState.SENT);
        assertThat(done.answered()).isTrue();
        assertThat(done.billsec()).isEqualTo(15);
        assertThat(done.charge()).isEqualTo(charge);
        assertThat(lost.state()).isEqualTo(TaskState.FAILED);
        assertThat(lost.charge().free()).isTrue();
        CampaignCounters c = counters(1);
        assertThat(c.sent()).isEqualTo(1);
        assertThat(c.failed()).isEqualTo(1);
        assertThat(c.pending()).isZero();
        assertThat(c.servedToday()).isEqualTo(2);
        assertThat(store.tasks).containsKeys("ad-btcl-1", "ad-btcl-2");
        assertThat(store.counters.get(1)).containsExactly(1, 1, 0);
        assertThat(service.complete(done, 99, null, "again")).as("a terminal task is not closed twice").isSameAs(done);
        assertThat(counters(1).sent()).isEqualTo(1);
    }

    @Test
    void the_quota_holds_under_concurrency_and_closes_the_campaign() throws Exception {
        service.reloaded("btcl", List.of(campaign(1, 0, 5, Targeting.ANY, CampaignPolicy.ALWAYS, VIDEO)));
        ViewRequest v = view("zone0", null, null);
        Placement p = service.rank(v).get(0);

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            int n = i;
            pool.submit(() -> {
                go.await();
                if (service.claim(p, v, "ad-" + n, null, null).isPresent()) claimed.incrementAndGet();
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(claimed.get()).as("five views bought, five claimed, never six").isEqualTo(5);
        assertThat(service.rank(v)).as("nothing left while the five are live").isEmpty();

        for (int i = 0; i < 5; i++) {
            Optional<CampaignTask> t = store.tasks.values().stream().filter(x -> x.state() == TaskState.PROCESSING).findFirst();
            if (t.isEmpty()) break;
            service.complete(t.get(), 15, TaskCharge.FREE, "viewed");
        }
        assertThat(counters(1).status()).isEqualTo("Complete");
        assertThat(store.completed).containsExactly(1);
        assertThat(service.rank(v)).isEmpty();
    }

    @Test
    void a_reload_keeps_the_live_views_and_takes_the_store_counters() {
        service.reloaded("btcl", List.of(campaign(1, 0, 10, Targeting.ANY, CampaignPolicy.ALWAYS, VIDEO)));
        ViewRequest v = view("zone0", null, null);
        CampaignTask live = service.claim(service.rank(v).get(0), v, "ad-1", null, null).orElseThrow();
        service.complete(service.claim(service.rank(v).get(0), v, "ad-2", null, null).orElseThrow(), 15, null, "viewed");
        assertThat(counters(1).sent()).isEqualTo(1);

        // the store now says 1 sent (it was told); the reload must not count it twice, and ad-1 is still pending
        Campaign fresh = new Campaign(1, "btcl", "c1", CampaignKind.AD, "Running", 701, null, null, null, 0, 10, 1, 0, 1, 10,
            CampaignPolicy.ALWAYS, Targeting.ANY, List.of(VIDEO), Map.of());
        service.reloaded("btcl", List.of(fresh));

        CampaignCounters c = counters(1);
        assertThat(c.sent()).isEqualTo(1);
        assertThat(c.pending()).as("the live view of the old row carries over").isEqualTo(1);
        service.complete(live, 15, null, "viewed");
        assertThat(counters(1).sent()).isEqualTo(2);
        assertThat(counters(1).pending()).isZero();

        service.reloaded("btcl", List.of());
        assertThat(service.rank(v)).as("a campaign that left the store leaves the service").isEmpty();
    }

    @Test
    void the_frequency_cap_hides_a_campaign_from_a_device_that_saw_it_enough_today() {
        service.reloaded("btcl", List.of(
            campaign(1, 9, 0, Targeting.ANY, new CampaignPolicy(List.of(), 2), VIDEO),
            campaign(2, 0, 0, Targeting.ANY, CampaignPolicy.ALWAYS, IMAGE)));
        ViewRequest phone = view("zone0", null, "aa:bb");
        for (int i = 0; i < 2; i++) service.claim(service.rank(phone).get(0), phone, "ad-" + i, null, null);

        assertThat(service.rank(phone)).extracting(p -> p.campaign().id()).as("capped for this device").containsExactly(2);
        assertThat(service.rank(view("zone0", null, "cc:dd"))).extracting(p -> p.campaign().id()).containsExactly(1, 2);
    }

    @Test
    void a_failing_store_is_logged_and_the_task_still_lives_and_ends() {
        service.reloaded("btcl", List.of(campaign(1, 0, 0, Targeting.ANY, CampaignPolicy.ALWAYS, VIDEO)));
        store.failWith = new IllegalStateException("db down");
        ViewRequest v = view("zone0", null, null);

        CampaignTask t = service.claim(service.rank(v).get(0), v, "ad-1", null, null).orElseThrow();
        CampaignTask done = service.complete(t, 15, null, "viewed");

        assertThat(done.state()).isEqualTo(TaskState.SENT);
        assertThat(counters(1).sent()).isEqualTo(1);
        assertThat(store.tasks).isEmpty();
    }

    @Test
    void an_unknown_tenant_ranks_nothing_and_claims_nothing() {
        service.reloaded("btcl", List.of(campaign(1, 0, 0, Targeting.ANY, CampaignPolicy.ALWAYS, VIDEO)));
        Placement p = service.rank(view("zone0", null, null)).get(0);
        assertThat(service.rank(ViewRequest.at("nobody", "zone0", null, null, null, null, NOON))).isEmpty();
        assertThat(service.claim(p, ViewRequest.at("nobody", "zone0", null, null, null, null, NOON), "x", null, null)).isEmpty();
    }

    private CampaignCounters counters(int id) { return service.counters("btcl").get(id); }
}
