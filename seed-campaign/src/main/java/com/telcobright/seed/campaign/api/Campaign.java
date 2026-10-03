package com.telcobright.seed.campaign.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * routesphere's {@code campaign} row as the service needs it — immutable, read into the tenant's context in one pass.
 *
 * @param status          the {@code enumjobstatus.Type} name ({@code Complete}, {@code Paused}, {@code Canceled} end a campaign)
 * @param partnerId       the campaign's partner: the sender (SMS) or the advertiser (AD); 0 = the operator's own
 * @param totalTaskCount  SMS: rows to send; AD: views bought, 0 = unlimited
 * @param sentTaskCount   done (SMS: dispatched; AD: completed views) — as the store knew it at load time
 * @param pendingTaskCount live (AD: views in progress) — as the store knew it at load time
 * @param defaultViewSeconds AD: how long a view must last when the creative says nothing ({@code campaign.FIELD2})
 * @param fields          the rest of the row worth carrying (message, externalCampaignId, field3..5, audioFilePath)
 */
public record Campaign(int id,
                       String tenantId,
                       String name,
                       CampaignKind kind,
                       String status,
                       int partnerId,
                       Instant expireAt,
                       Instant scheduleStart,
                       Instant scheduleEnd,
                       int priority,
                       int totalTaskCount,
                       int sentTaskCount,
                       int failedTaskCount,
                       int pendingTaskCount,
                       int defaultViewSeconds,
                       CampaignPolicy policy,
                       Targeting targeting,
                       List<Creative> creatives,
                       Map<String, String> fields) {

    public Campaign {
        creatives = List.copyOf(creatives);
        fields = Map.copyOf(fields);
        if (policy == null) policy = CampaignPolicy.ALWAYS;
        if (targeting == null) targeting = Targeting.ANY;
    }

    public boolean terminal() {
        if (status == null) return false;
        return switch (status.trim().toLowerCase()) {
            case "complete", "completed", "paused", "canceled", "cancelled" -> true;
            default -> false;
        };
    }

    public boolean unlimited() { return totalTaskCount <= 0; }

    public Campaign withStatus(String newStatus) {
        return new Campaign(id, tenantId, name, kind, newStatus, partnerId, expireAt, scheduleStart, scheduleEnd, priority,
            totalTaskCount, sentTaskCount, failedTaskCount, pendingTaskCount, defaultViewSeconds, policy, targeting, creatives, fields);
    }

    /** The same campaign with other counters — the store's, when they live outside the campaign's row ({@code campaign_counter}). */
    public Campaign withCounters(int sent, int failed, int pending) {
        return new Campaign(id, tenantId, name, kind, status, partnerId, expireAt, scheduleStart, scheduleEnd, priority,
            totalTaskCount, sent, failed, pending, defaultViewSeconds, policy, targeting, creatives, fields);
    }

    /** The smallest open ad campaign: one creative, every zone, no quota, no advertiser. */
    public static Campaign openAd(int id, String tenantId, String name, Creative creative, int viewSeconds) {
        return new Campaign(id, tenantId, name, CampaignKind.AD, "Running", 0, null, null, null, 0, 0, 0, 0, 0,
            viewSeconds, CampaignPolicy.ALWAYS, Targeting.ANY, List.of(creative), Map.of());
    }
}
