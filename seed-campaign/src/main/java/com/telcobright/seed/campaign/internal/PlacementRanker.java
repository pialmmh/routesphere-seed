package com.telcobright.seed.campaign.internal;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.Creative;
import com.telcobright.seed.campaign.api.Placement;
import com.telcobright.seed.campaign.api.ViewRequest;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The ranking, as pure as it can be: runnable → targeted → quota → cap, then order by specificity (most constrained
 * match first — the ad's "longest prefix"), campaign priority (higher first), fewest served today (fair share),
 * and the lower id for a stable tie. The creative is the campaign's next in rotation.
 */
final class PlacementRanker {

    private final ZoneId zone;

    PlacementRanker(ZoneId zone) { this.zone = zone; }

    List<Placement> rank(TenantCampaigns tenant, ViewRequest view) {
        List<Ranked> hits = new ArrayList<>();
        for (LiveCampaign live : tenant.all()) {
            Campaign c = live.row;
            if (RunnableRule.closed(c, view.at(), zone).isPresent()) continue;
            int specificity = c.targeting().specificity(view.facts());
            if (specificity < 0) continue;
            if (!live.quotaLeft()) continue;
            if (c.policy().capped() && tenant.cap.servedToday(c.id(), view.device(), view.at()) >= c.policy().frequencyCapPerDevicePerDay()) continue;
            if (c.creatives().isEmpty()) continue;
            hits.add(new Ranked(live, specificity, live.servedToday(view.at(), zone)));
        }
        hits.sort(Comparator.<Ranked>comparingInt(r -> -r.specificity)
            .thenComparingInt(r -> -r.live.row.priority())
            .thenComparingInt(r -> r.servedToday)
            .thenComparingInt(r -> r.live.row.id()));
        List<Placement> out = new ArrayList<>(hits.size());
        for (Ranked r : hits) {
            Creative creative = r.live.rotate();
            if (creative != null) out.add(new Placement(r.live.row, creative, r.specificity));
        }
        return out;
    }

    private record Ranked(LiveCampaign live, int specificity, int servedToday) {}
}
