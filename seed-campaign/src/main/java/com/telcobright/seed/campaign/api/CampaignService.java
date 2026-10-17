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

    /**
     * Take one task of the placement — the task is BORN here (its row INSERTed {@code PROCESSING} under its campaign, the campaign's pending
     * +1); empty when the quota or the cap went in the meantime. Since ARCH-0093 the ad does not use it: its task is minted by its runner
     * ({@link #handedToCore}) and takes its campaign at the claim ({@link #assign}). Kept for a client whose task is born at the claim.
     */
    Optional<CampaignTask> claim(Placement placement, ViewRequest view, String taskId, String subject, String clientRef);

    /**
     * LAYER 1 → LAYER 2 (ARCH-0093; the owner, 2026-10-17: "the campaign runner shall be responsible for sending CampaignTasks to the core"):
     * a runner hands the core a task it MINTED ({@link CampaignTask#minted} — the request's facts, no campaign). The row is made
     * {@code PROCESSING} (STATE 1, the SMS runner's {@code markAsProcessing}) under {@link CampaignTask#NO_CAMPAIGN}; no campaign's counter
     * moves, for none has taken it. The core's claim is {@link #assign}; a request the core refuses is {@link #closed} {@code FAILED} with
     * its cause, still under no campaign. A task that has a campaign is refused ({@link IllegalArgumentException}).
     */
    CampaignTask handedToCore(CampaignTask minted);

    /**
     * The core's claim for a task that HAS its row ({@link #handedToCore}): the campaign's quota and the device's cap are taken as
     * {@link #claim} takes them, the row is UPDATEd with the campaign, its partner and the creative, and the campaign's pending moves +1 —
     * what {@code claim} did, less the INSERT. Empty when the quota or the cap went in the meantime: the task stays as it was and the
     * caller tries its next campaign. A task that has its campaign already is refused ({@link IllegalArgumentException}).
     */
    Optional<CampaignTask> assign(CampaignTask task, Placement placement, ViewRequest view);

    /** The task's subject saw it (an ad: shown on screen). */
    CampaignTask answered(CampaignTask task);

    /** The task is done — counters, the store, and the campaign closes itself when its quota is reached. */
    CampaignTask complete(CampaignTask task, int watchedSec, TaskCharge charge, String cause);

    /** The task failed for good. */
    CampaignTask fail(CampaignTask task, int watchedSec, String cause);

    /**
     * The row the SESSION BASE closed ({@code SENT} or {@code FAILED}, the end's facts on it — seed-sessionflow's CLOSE_TASK step):
     * the counters and the store take it exactly as {@link #complete} / {@link #fail} would, and the campaign closes itself when its
     * quota is reached. A row no campaign took ({@link CampaignTask#assigned} false: a request the core refused) is written as it is and
     * moves no counter. A row that is not terminal is refused ({@link IllegalArgumentException}): the base closes, this records.
     */
    CampaignTask closed(CampaignTask closedTask);

    Map<Integer, CampaignCounters> counters(String tenantId);

    Optional<Campaign> campaign(String tenantId, int campaignId);
}
