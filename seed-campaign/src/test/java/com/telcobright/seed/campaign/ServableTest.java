package com.telcobright.seed.campaign;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignKind;
import com.telcobright.seed.campaign.api.CampaignPolicy;
import com.telcobright.seed.campaign.api.CampaignService;
import com.telcobright.seed.campaign.api.Creative;
import com.telcobright.seed.campaign.api.MediaKind;
import com.telcobright.seed.campaign.api.Placement;
import com.telcobright.seed.campaign.api.Targeting;
import com.telcobright.seed.campaign.api.TimeBand;
import com.telcobright.seed.campaign.api.ViewRequest;
import com.telcobright.seed.campaign.internal.DefaultCampaignService;
import com.telcobright.seed.campaign.testkit.InMemoryCampaignStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-P item 1 — ONE campaign's servability without ranking the tenant (the ad lane's capped ROUTED campaign asked {@code rank(view)} once
 * per view to learn one campaign's answer). {@link CampaignService#servable} answers EXACTLY what {@link CampaignService#rank} answers for
 * that campaign — the same rule (runnable, targeted, quota left, the device under its cap, creatives present), the same specificity, the
 * same creative of the rotation — and rotates the campaign's creative only when it answers a placement, once, as {@code rank} does.
 */
class ServableTest {

    static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    static final Instant NOON = Instant.parse("2026-09-27T06:00:00Z");   // Sunday 12:00 in Dhaka (a weekday)
    static final Creative A = Creative.stream("a", MediaKind.VIDEO, "vod-a", 15);
    static final Creative B = Creative.stream("b", MediaKind.IMAGE, "vod-b", 0);
    static final Creative C = Creative.text("c", "the third");
    static final int ID = 1, NEIGHBOUR = 2;

    static Campaign campaign(int id, int total, Targeting t, CampaignPolicy p, Creative... creatives) {
        return new Campaign(id, "btcl", "c" + id, CampaignKind.AD, "Running", 700 + id, null, null, null, 0, total, 0, 0, 0, 10, p, t,
            List.of(creatives), Map.of());
    }

    static Campaign window(Instant expireAt, Instant scheduleStart, Instant scheduleEnd) {
        return new Campaign(ID, "btcl", "windowed", CampaignKind.AD, "Running", 701, expireAt, scheduleStart, scheduleEnd, 0, 0, 0, 0, 0, 10,
            CampaignPolicy.ALWAYS, Targeting.ANY, List.of(A, B), Map.of());
    }

    static ViewRequest view(String zone, String site, String device) { return ViewRequest.at("btcl", zone, site, "dhaka", "wifi-gw2", device, NOON); }

    static ViewRequest phone(String device) { return view("zone0", "moghbazar", device); }

    static DefaultCampaignService service() { return new DefaultCampaignService(new InMemoryCampaignStore(), Clock.fixed(NOON, DHAKA), DHAKA); }

    /**
     * One row of the table: the campaign as loaded (beside an always-open neighbour), the devices whose views were claimed before (each
     * claim takes a slot of the quota and counts the device's cap; a claim never rotates), the view, and whether it serves.
     */
    record Case(String name, Campaign campaign, List<String> claimedBefore, ViewRequest view, boolean serves) {
        @Override public String toString() { return name; }
    }

    static Stream<Case> theRulesPredicates() {
        CampaignPolicy nightOnly = new CampaignPolicy(List.of(TimeBand.allowDaily(LocalTime.of(22, 0), LocalTime.of(23, 59))), null);
        CampaignPolicy twicePerDevice = new CampaignPolicy(List.of(), 2);
        Targeting zoneAndSite = Targeting.of("zone", "zone0").and("site", "moghbazar");
        return Stream.of(
            new Case("open to every zone: serves, specificity 0", campaign(ID, 0, Targeting.ANY, CampaignPolicy.ALWAYS, A, B), List.of(), phone("aa:bb"), true),
            new Case("targeted by zone and site: serves, specificity 2", campaign(ID, 0, zoneAndSite, CampaignPolicy.ALWAYS, A, B), List.of(), phone("aa:bb"), true),
            new Case("runnable: paused", campaign(ID, 0, Targeting.ANY, CampaignPolicy.ALWAYS, A, B).withStatus("Paused"), List.of(), phone("aa:bb"), false),
            new Case("runnable: complete", campaign(ID, 0, Targeting.ANY, CampaignPolicy.ALWAYS, A, B).withStatus("Complete"), List.of(), phone("aa:bb"), false),
            new Case("runnable: expired", window(NOON.minusSeconds(1), null, null), List.of(), phone("aa:bb"), false),
            new Case("runnable: not started", window(null, NOON.plusSeconds(60), null), List.of(), phone("aa:bb"), false),
            new Case("runnable: its schedule ended", window(null, null, NOON), List.of(), phone("aa:bb"), false),
            new Case("runnable: outside its time band", campaign(ID, 0, Targeting.ANY, nightOnly, A, B), List.of(), phone("aa:bb"), false),
            new Case("targeted: another zone", campaign(ID, 0, Targeting.of("zone", "zone1"), CampaignPolicy.ALWAYS, A, B), List.of(), phone("aa:bb"), false),
            new Case("targeted: the site is not one it allows", campaign(ID, 0, zoneAndSite, CampaignPolicy.ALWAYS, A, B), List.of(), view("zone0", "gulshan", "aa:bb"), false),
            new Case("quota: its one view bought is live", campaign(ID, 1, Targeting.ANY, CampaignPolicy.ALWAYS, A, B), List.of("cc:dd"), phone("aa:bb"), false),
            new Case("quota: two bought, one live — serves", campaign(ID, 2, Targeting.ANY, CampaignPolicy.ALWAYS, A, B), List.of("cc:dd"), phone("aa:bb"), true),
            new Case("cap: the device saw it twice today", campaign(ID, 0, Targeting.ANY, twicePerDevice, A, B), List.of("aa:bb", "aa:bb"), phone("aa:bb"), false),
            new Case("cap: another device — serves", campaign(ID, 0, Targeting.ANY, twicePerDevice, A, B), List.of("aa:bb", "aa:bb"), phone("cc:dd"), true),
            new Case("cap: once is under the cap — serves", campaign(ID, 0, Targeting.ANY, twicePerDevice, A, B), List.of("aa:bb"), phone("aa:bb"), true),
            new Case("creatives: none", campaign(ID, 0, Targeting.ANY, CampaignPolicy.ALWAYS), List.of(), phone("aa:bb"), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("theRulesPredicates")
    void servable_answers_what_rank_answers_for_the_campaign(Case c) {
        DefaultCampaignService ranking = fedWith(c), checking = fedWith(c);       // fed alike: their rotations stay in step

        Optional<Placement> ranked = ranking.rank(c.view()).stream().filter(p -> p.campaign().id() == ID).findFirst();
        Optional<Placement> servable = checking.servable("btcl", ID, c.view());

        assertThat(servable).as(c.name() + ": the same placement — campaign, creative, specificity — or none").isEqualTo(ranked);
        assertThat(servable.isPresent()).as(c.name()).isEqualTo(c.serves());
        assertThat(checking.servable("btcl", NEIGHBOUR, c.view())).as("the open neighbour serves whatever this campaign answers").isPresent();
    }

    private static DefaultCampaignService fedWith(Case c) {
        DefaultCampaignService s = service();
        s.reloaded("btcl", List.of(c.campaign(), campaign(NEIGHBOUR, 0, Targeting.ANY, CampaignPolicy.ALWAYS, C)));
        int n = 0;
        for (String device : c.claimedBefore()) {
            Placement any = new Placement(c.campaign(), c.campaign().creatives().get(0), 0);
            assertThat(s.claim(any, phone(device), "before-" + n++, null, null)).as("the history's claim").isPresent();
        }
        return s;
    }

    @Test
    void a_capped_campaign_answers_empty_for_the_device_that_saw_it_enough_and_serves_another() {
        DefaultCampaignService s = service();
        s.reloaded("btcl", List.of(campaign(ID, 0, Targeting.ANY, new CampaignPolicy(List.of(), 2), A, B)));
        for (int i = 0; i < 2; i++) s.claim(s.servable("btcl", ID, phone("aa:bb")).orElseThrow(), phone("aa:bb"), "ad-" + i, null, null);

        assertThat(s.servable("btcl", ID, phone("aa:bb"))).as("capped for this device").isEmpty();
        assertThat(s.servable("btcl", ID, phone("AA:BB"))).as("the cap's device key is case-blind").isEmpty();
        assertThat(s.servable("btcl", ID, phone("cc:dd"))).as("another device is not capped").isPresent();
    }

    @Test
    void a_placement_answered_rotates_the_campaigns_creative_once_as_rank_does_and_an_empty_answer_rotates_nothing() {
        DefaultCampaignService s = service();
        s.reloaded("btcl", List.of(campaign(ID, 0, Targeting.ANY, new CampaignPolicy(List.of(), 1), A, B, C)));

        assertThat(creativeOf(s.servable("btcl", ID, phone("aa:bb")))).as("the first in rotation").isEqualTo("a");
        assertThat(creativeOf(s.rank(phone("aa:bb")).stream().findFirst())).as("rank goes on with the SAME rotation").isEqualTo("b");
        Placement third = s.servable("btcl", ID, phone("aa:bb")).orElseThrow();
        assertThat(third.creative().id()).isEqualTo("c");
        s.claim(third, phone("aa:bb"), "ad-1", null, null);                                // the phone saw it once: its cap is 1

        assertThat(s.servable("btcl", ID, phone("aa:bb"))).as("capped").isEmpty();
        assertThat(s.servable("btcl", ID, phone("aa:bb"))).as("still capped").isEmpty();

        assertThat(creativeOf(s.servable("btcl", ID, phone("cc:dd")))).as("two empty answers rotated nothing: the next is the fourth draw").isEqualTo("a");
        assertThat(creativeOf(s.rank(phone("cc:dd")).stream().findFirst())).isEqualTo("b");
    }

    @Test
    void servable_rotates_only_the_campaign_it_answers_while_rank_rotates_every_campaign_it_ranks() {
        DefaultCampaignService s = service();
        s.reloaded("btcl", List.of(campaign(ID, 0, Targeting.ANY, CampaignPolicy.ALWAYS, A, B), campaign(NEIGHBOUR, 0, Targeting.ANY, CampaignPolicy.ALWAYS, A, B)));

        assertThat(creativeOf(s.servable("btcl", ID, phone("aa:bb")))).isEqualTo("a");
        assertThat(creativeOf(s.servable("btcl", NEIGHBOUR, phone("aa:bb")))).as("the neighbour was not rotated by the first answer").isEqualTo("a");
        assertThat(s.rank(phone("aa:bb"))).extracting(p -> p.campaign().id() + ":" + p.creative().id()).containsExactly("1:b", "2:b");
        assertThat(creativeOf(s.servable("btcl", ID, phone("aa:bb")))).isEqualTo("a");
        assertThat(creativeOf(s.servable("btcl", NEIGHBOUR, phone("aa:bb")))).isEqualTo("a");
    }

    @Test
    void an_unknown_campaign_or_tenant_answers_empty_and_a_view_of_another_tenant_is_refused() {
        DefaultCampaignService s = service();
        s.reloaded("btcl", List.of(campaign(ID, 0, Targeting.ANY, CampaignPolicy.ALWAYS, A)));
        ViewRequest nobodys = ViewRequest.at("nobody", "zone0", null, null, null, "aa:bb", NOON);

        assertThat(s.servable("btcl", 99, phone("aa:bb"))).as("no such campaign").isEmpty();
        assertThat(s.servable("nobody", ID, nobodys)).as("no such tenant").isEmpty();
        assertThatThrownBy(() -> s.servable("btcl", ID, nobodys)).as("a claim takes the campaign of the VIEW's tenant: another is refused")
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("btcl").hasMessageContaining("nobody");
        assertThat(creativeOf(s.servable("btcl", ID, phone("aa:bb")))).as("a refused ask rotated nothing").isEqualTo("a");
    }

    private static String creativeOf(Optional<Placement> placement) { return placement.orElseThrow().creative().id(); }
}
