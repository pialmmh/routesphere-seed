package com.telcobright.seed.campaign.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The generic campaign service: given a tenant's open campaigns (fed on every context reload) it ranks the campaigns
 * that may serve a request, claims ONE task for the chosen one, and closes the task as completed or failed — keeping
 * the campaign counters and telling the store. A runner (SMS) or an API (ads) drives it; it fires nothing itself.
 *
 * <p>Rules, in order: runnable (status, expiry, schedule window, TimeBands) → targeted (zone/site/district/gw) →
 * quota ({@code totalTaskCount}) → frequency cap per device → rank (specificity, priority, fewest served today).
 * All of it is in memory; the store is told after the fact.
 */
public interface CampaignService {

    /** Replace what the service knows of the tenant's campaigns (a context reload). Live counts survive. */
    void reloaded(String tenantId, List<Campaign> campaigns);

    /** The campaigns that may serve this request, best first, each with the creative it rotates to. */
    List<Placement> rank(ViewRequest view);

    /** Take one task of the placement — empty when the quota or the cap went in the meantime. */
    Optional<CampaignTask> claim(Placement placement, ViewRequest view, String taskId, String subject, String clientRef);

    /** The task's subject saw it (an ad: shown on screen). */
    CampaignTask answered(CampaignTask task);

    /** The task is done — counters, the store, and the campaign closes itself when its quota is reached. */
    CampaignTask complete(CampaignTask task, int watchedSec, TaskCharge charge, String cause);

    /** The task failed for good. */
    CampaignTask fail(CampaignTask task, int watchedSec, String cause);

    Map<Integer, CampaignCounters> counters(String tenantId);

    Optional<Campaign> campaign(String tenantId, int campaignId);
}
