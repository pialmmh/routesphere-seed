package com.telcobright.seed.campaign.spi;

import com.telcobright.seed.campaign.api.CampaignTask;

/**
 * ONE change the service tells a store: a task's row as it is now, a campaign's counters moved, a campaign that reached its quota.
 * The four writes of {@link CampaignStore} as values — so that a change can wait in a queue, be written with others in one
 * transaction ({@link CampaignStore#write}), and be kept as a line on disk while the store does not answer.
 */
public sealed interface StoreChange {

    /** The tenant whose store the change is told to. */
    String tenantId();

    /** The task was claimed: its row is made. */
    record TaskInserted(CampaignTask task) implements StoreChange {
        @Override public String tenantId() { return task.tenantId(); }
    }

    /** The task moved on (answered, completed, failed): its row as it is now. */
    record TaskUpdated(CampaignTask task) implements StoreChange {
        @Override public String tenantId() { return task.tenantId(); }
    }

    /** The deltas of a campaign's SENT / FAILED / PENDING counts. */
    record CountersBumped(String tenantId, int campaignId, int sent, int failed, int pending) implements StoreChange {}

    /** The campaign reached its quota: its status becomes Complete. */
    record CampaignCompleted(String tenantId, int campaignId) implements StoreChange {}
}
