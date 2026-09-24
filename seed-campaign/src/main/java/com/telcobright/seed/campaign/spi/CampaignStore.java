package com.telcobright.seed.campaign.spi;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignTask;

import java.util.List;

/**
 * Where campaigns and tasks live: routesphere's MySQL schema ({@code jdbc/}), or memory (tests). One store serves one
 * tenant's database; {@code tenantId} rides on the records. Every write must be safe to repeat (the product may retry).
 *
 * <p>The service calls the store on the caller's thread; a product that must never wait wraps it in a queue.
 */
public interface CampaignStore {

    /** Every campaign of the kind this store serves that is not archived — terminal ones included (the rule decides). */
    List<Campaign> campaigns(String tenantId);

    void insertTask(CampaignTask task);

    void updateTask(CampaignTask task);

    /** Add the deltas to {@code SENT/FAILED/PENDING_TASK_COUNT}. */
    void bumpCounters(String tenantId, int campaignId, int sentDelta, int failedDelta, int pendingDelta);

    /** The campaign reached its quota: {@code STATUS = Complete}. */
    void markComplete(String tenantId, int campaignId);
}
