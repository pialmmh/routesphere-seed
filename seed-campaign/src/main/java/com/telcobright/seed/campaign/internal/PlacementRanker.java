package com.telcobright.seed.campaign.internal;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.Placement;
import com.telcobright.seed.campaign.api.ViewRequest;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The ranking, as pure as it can be. One rule ({@link #mayServe}): runnable → targeted → quota → cap → creatives. Then the order:
 * specificity (the most constrained match first, the ad's "longest prefix"), campaign priority (higher first), fewest served today (fair
 * share), and the lower id for a stable tie. The creative is the campaign's next in rotation ({@link #placementOf}).
 *
 * <p>P1-P · {@link #servable} answers ONE campaign with the same rule and the same rotation step, through one get of the campaign by id.
 */
final class PlacementRanker {

    /** The ranking's order: the most specific first, then the higher priority, the fewest served today, the lower id. */
    private static final Comparator<Hit> BEST_FIRST = Comparator.<Hit>comparingInt(hit -> -hit.specificity())
        .thenComparingInt(hit -> -hit.row().priority())
        .thenComparingInt(Hit::servedToday)
        .thenComparingInt(hit -> hit.row().id());

    private final ZoneId zone;

    PlacementRanker(ZoneId zone) { this.zone = zone; }

    /** Every campaign of the tenant that may serve the view, best first, each with the creative it rotates to. */
    List<Placement> rank(TenantCampaigns tenant, ViewRequest view) {
        List<Hit> hits = hitsOf(tenant, view);
        hits.sort(BEST_FIRST);
        return placementsOf(hits);
    }

    /**
     * ONE campaign by the ranking's rule: its placement as {@link #rank} would give it, or empty. One get by its id, never a walk of the
     * tenant. The campaign's creative rotates only when the answer is a placement, once, as {@code rank} rotates it. An empty answer rotates
     * nothing.
     */
    Optional<Placement> servable(TenantCampaigns tenant, int campaignId, ViewRequest view) {
        LiveCampaign live = tenant.get(campaignId);
        if (live == null) return Optional.empty();
        return mayServe(tenant, live, view).map(PlacementRanker::placementOf);
    }

    /**
     * THE rule, written once: {@link #rank} asks it of every campaign of the tenant, {@link #servable} of one. The campaign is runnable now
     * ({@link RunnableRule}), targets the view (specificity ≥ 0), has quota left, the device is under its daily cap, and it has creatives.
     * Empty means the campaign may not serve the view. The row is read ONCE, so every predicate and the placement see the same row. Reads
     * only: nothing rotates, nothing is counted.
     */
    Optional<Hit> mayServe(TenantCampaigns tenant, LiveCampaign live, ViewRequest view) {
        Campaign row = live.row;
        if (RunnableRule.closed(row, view.at(), zone).isPresent()) return Optional.empty();
        int specificity = row.targeting().specificity(view.facts());
        if (specificity < 0) return Optional.empty();
        if (!live.quotaLeft()) return Optional.empty();
        if (cappedFor(tenant, row, view)) return Optional.empty();
        if (row.creatives().isEmpty()) return Optional.empty();
        return Optional.of(new Hit(live, row, specificity, live.servedToday(view.at(), zone)));
    }

    private List<Hit> hitsOf(TenantCampaigns tenant, ViewRequest view) {
        List<Hit> hits = new ArrayList<>();
        for (LiveCampaign live : tenant.all()) mayServe(tenant, live, view).ifPresent(hits::add);
        return hits;
    }

    private static List<Placement> placementsOf(List<Hit> hits) {
        List<Placement> out = new ArrayList<>(hits.size());
        for (Hit hit : hits) out.add(placementOf(hit));
        return out;
    }

    /** The device saw the campaign its daily cap's worth of times today (a campaign with no cap: never). */
    private static boolean cappedFor(TenantCampaigns tenant, Campaign row, ViewRequest view) {
        return row.policy().capped() && tenant.cap.servedToday(row.id(), view.device(), view.at()) >= row.policy().frequencyCapPerDevicePerDay();
    }

    /** The rotation step, the same for both roads: the next creative of the row the rule judged; the campaign's rotation moves on by one. */
    private static Placement placementOf(Hit hit) {
        return new Placement(hit.row(), hit.live().rotate(hit.row()), hit.specificity());
    }

    /** A campaign the rule let through: the row it judged, its specificity and today's served count (the order's). */
    record Hit(LiveCampaign live, Campaign row, int specificity, int servedToday) {}
}
