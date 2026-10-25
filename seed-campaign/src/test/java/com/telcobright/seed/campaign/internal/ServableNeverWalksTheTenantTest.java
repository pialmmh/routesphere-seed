package com.telcobright.seed.campaign.internal;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.Creative;
import com.telcobright.seed.campaign.api.MediaKind;
import com.telcobright.seed.campaign.api.ViewRequest;
import com.telcobright.seed.campaign.testkit.InMemoryCampaignStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-P item 1 — {@code servable} is ONE get of the campaign by its id: the tenant's campaigns are never walked for it (the walk is
 * {@code TenantCampaigns.all()}, counted). The ranking walks them once per view; the one-campaign check never, however many campaigns the
 * tenant has and however often it is asked.
 */
class ServableNeverWalksTheTenantTest {

    static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
    static final Instant NOON = Instant.parse("2026-09-27T06:00:00Z");

    @Test
    void servable_asks_the_campaign_by_its_id_and_never_walks_the_tenants_campaigns() {
        DefaultCampaignService service = new DefaultCampaignService(new InMemoryCampaignStore(), Clock.fixed(NOON, DHAKA), DHAKA);
        List<Campaign> fifty = new ArrayList<>();
        for (int id = 1; id <= 50; id++) fifty.add(Campaign.openAd(id, "btcl", "c" + id, Creative.stream("v" + id, MediaKind.VIDEO, "vod-" + id, 15), 10));
        service.reloaded("btcl", fifty);
        ViewRequest view = ViewRequest.at("btcl", "zone0", null, null, null, "aa:bb", NOON);
        long before = service.campaignWalks("btcl");

        for (int i = 0; i < 1_000; i++) assertThat(service.servable("btcl", 1 + i % 50, view)).isPresent();
        assertThat(service.servable("btcl", 99, view)).isEmpty();

        assertThat(service.campaignWalks("btcl")).as("1 001 one-campaign checks: not one walk of the tenant's campaigns").isEqualTo(before);
        assertThat(service.rank(view)).hasSize(50);
        assertThat(service.campaignWalks("btcl")).as("the ranking walks them once per view").isEqualTo(before + 1);
    }
}
