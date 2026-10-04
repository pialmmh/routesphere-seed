package com.telcobright.seed.campaign.spi;

import com.telcobright.seed.campaign.api.Campaign;
import com.telcobright.seed.campaign.api.CampaignTask;

import java.util.List;

/**
 * Where campaigns and tasks live: routesphere's MySQL schema ({@code jdbc/}), or memory (tests). One store serves one
 * tenant's database; {@code tenantId} rides on the records. Every write must be safe to repeat (the product may retry).
 *
 * <p>The service calls the store on the caller's thread; a product that must never wait wraps it in a queue
 * ({@code writer/QueuedCampaignStore}: one writer per store, batches, a failed batch tried again, a journal for what was not written).
 */
public interface CampaignStore {

    /** The cause of a task closed by {@link #repairAfterRestart}: its process stopped before the task ended. */
    String LOST_AT_RESTART = "LOST_AT_RESTART";

    /** Every campaign of the kind this store serves that is not archived — terminal ones included (the rule decides). */
    List<Campaign> campaigns(String tenantId);

    void insertTask(CampaignTask task);

    void updateTask(CampaignTask task);

    /** Add the deltas to {@code SENT/FAILED/PENDING_TASK_COUNT}. */
    void bumpCounters(String tenantId, int campaignId, int sentDelta, int failedDelta, int pendingDelta);

    /** The campaign reached its quota: {@code STATUS = Complete}. */
    void markComplete(String tenantId, int campaignId);

    /**
     * A batch of changes, in their order. A store that has transactions makes the batch ONE: every task row of it, and for each
     * campaign ONE counter statement with the batch's sums — all of it or none, so a batch that failed may be written again whole.
     * A store without transactions (memory) takes them one by one, as here.
     */
    default void write(List<StoreChange> batch) {
        for (StoreChange change : batch) {
            switch (change) {
                case StoreChange.TaskInserted c -> insertTask(c.task());
                case StoreChange.TaskUpdated c -> updateTask(c.task());
                case StoreChange.CountersBumped c -> bumpCounters(c.tenantId(), c.campaignId(), c.sent(), c.failed(), c.pending());
                case StoreChange.CampaignCompleted c -> markComplete(c.tenantId(), c.campaignId());
            }
        }
    }

    /**
     * A START repairs what a dead process left. Called once, before the first task of this process, by the ONE process that writes
     * this tenant's tasks: none of its tasks is live yet, so every task of {@code tenantName} that is not final is closed (failed, with
     * {@code cause}), and the campaigns' counters are made to say what the task rows say. A store that keeps nothing across a restart
     * has nothing to repair.
     *
     * @param tenantName the tenant as the store's task rows name it
     * @param cause      the end cause of a task closed here ({@link #LOST_AT_RESTART})
     */
    default StoreRepair repairAfterRestart(String tenantName, String cause, java.time.Instant at) { return StoreRepair.NOTHING; }
}
