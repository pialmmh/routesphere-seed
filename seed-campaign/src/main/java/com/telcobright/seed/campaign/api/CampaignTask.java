package com.telcobright.seed.campaign.api;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * routesphere's {@code campaign_task} row for ONE task, immutable — each step of its life returns the next row.
 * The voice columns carry a view's lifecycle ({@code START_TIME_MILLIS} = minted, {@code ANSWER_TIME_MILLIS} = shown,
 * {@code END_TIME_MILLIS} = ended, {@code BILLSEC} = watched seconds, {@code HANGUP_CAUSE} = the end cause), the
 * billing columns carry the charge, {@code TASK_DETAIL_JSON} the rest.
 *
 * @param subject   {@code PHONE_NUMBER} (NOT NULL): the MSISDN, or the MAC when there is none
 * @param creativeId {@code MESSAGE} for an ad task
 */
public record CampaignTask(String uniqueId,
                           String tenantId,
                           int campaignId,
                           int partnerId,
                           CampaignKind kind,
                           String subject,
                           String creativeId,
                           String zone,
                           String site,
                           String clientRef,
                           TaskState state,
                           Instant createdAt,
                           Instant answeredAt,
                           Instant endedAt,
                           int billsec,
                           String endCause,
                           TaskCharge charge,
                           Map<String, Object> detail) {

    public CampaignTask {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    /**
     * {@code CAMPAIGN_ID} of a task no campaign has taken yet — the ad's task between its runner's hand-over and the core's claim, and for
     * ever on a request no campaign could serve (ARCH-0093, the owner's two layers). It is 0, not NULL: the column is {@code NOT NULL} in
     * every schema (routesphere's, the model's, this library's) and no campaign has the id 0 (every identity starts at 1).
     */
    public static final int NO_CAMPAIGN = 0;

    /**
     * LAYER 1's task (ARCH-0093): what the campaign RUNNER mints for a request — its facts and no campaign, {@code CREATED} (STATE 6), as an
     * SMS recipient's row is before the runner's tick. The runner hands it to the core ({@link #processing}); the core's claim gives it its
     * campaign ({@link #assigned}). {@code subject} blank = {@code unknown}.
     */
    public static CampaignTask minted(String uniqueId, String tenantId, CampaignKind kind, String subject, String zone, String site, String clientRef,
                                      Instant at, Map<String, Object> detail) {
        return new CampaignTask(uniqueId, tenantId, NO_CAMPAIGN, 0, kind, subject == null || subject.isBlank() ? "unknown" : subject, null, zone, site,
            clientRef, TaskState.CREATED, at, null, null, 0, null, null, detail);
    }

    /** Has a campaign taken this task? False from the runner's hand-over to the core's claim, and for ever on a request the core refused. */
    public boolean assigned() { return campaignId > NO_CAMPAIGN; }

    /** Handed to the core: {@code PROCESSING} (STATE 1) — the SMS runner's {@code markAsProcessing}; nothing else of the row changes. */
    public CampaignTask processing() {
        return new CampaignTask(uniqueId, tenantId, campaignId, partnerId, kind, subject, creativeId, zone, site, clientRef,
            TaskState.PROCESSING, createdAt, answeredAt, endedAt, billsec, endCause, charge, detail);
    }

    /**
     * The core's claim: the task takes its campaign, the campaign's partner and the creative it plays (the SMS task knows its campaign at
     * birth, for its list belongs to one; the ad's learns it from routing); {@code more} joins the detail. The state does not move.
     */
    public CampaignTask assigned(Campaign campaign, Creative creative, Map<String, Object> more) {
        Map<String, Object> d = new HashMap<>(detail);
        if (more != null) d.putAll(more);
        return new CampaignTask(uniqueId, tenantId, campaign.id(), campaign.partnerId(), kind, subject, creative == null ? creativeId : creative.id(), zone, site,
            clientRef, state, createdAt, answeredAt, endedAt, billsec, endCause, charge, d);
    }

    public boolean answered() { return answeredAt != null; }

    public CampaignTask answered(Instant at) {
        return new CampaignTask(uniqueId, tenantId, campaignId, partnerId, kind, subject, creativeId, zone, site, clientRef,
            state, createdAt, at, endedAt, billsec, endCause, charge, detail);
    }

    /** The task is done: {@code STATE 11} (routesphere's SENT — for an ad, the view completed). */
    public CampaignTask completed(Instant at, int watchedSec, TaskCharge paid, String cause) {
        return new CampaignTask(uniqueId, tenantId, campaignId, partnerId, kind, subject, creativeId, zone, site, clientRef,
            TaskState.SENT, createdAt, answeredAt, at, watchedSec, cause, paid == null ? TaskCharge.FREE : paid, detail);
    }

    /** The task failed for good: {@code STATE 5}. Nothing is charged. */
    public CampaignTask failed(Instant at, int watchedSec, String cause) { return failed(at, watchedSec, cause, TaskCharge.FREE); }

    /**
     * The task failed for good: {@code STATE 5} — and it still cost {@code paid}: the session base closes a failed session with
     * what its settlement kept (an ad admitted and charged before the show, under the owner's rule; a call that ran and then failed).
     */
    public CampaignTask failed(Instant at, int watchedSec, String cause, TaskCharge paid) {
        return new CampaignTask(uniqueId, tenantId, campaignId, partnerId, kind, subject, creativeId, zone, site, clientRef,
            TaskState.FAILED, createdAt, answeredAt, at, watchedSec, cause, paid == null ? TaskCharge.FREE : paid, detail);
    }

    public CampaignTask withDetail(String key, Object value) {
        Map<String, Object> d = new HashMap<>(detail);
        if (value == null) d.remove(key); else d.put(key, value);
        return new CampaignTask(uniqueId, tenantId, campaignId, partnerId, kind, subject, creativeId, zone, site, clientRef,
            state, createdAt, answeredAt, endedAt, billsec, endCause, charge, d);
    }
}
