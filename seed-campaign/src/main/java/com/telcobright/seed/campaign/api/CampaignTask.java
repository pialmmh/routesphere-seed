package com.telcobright.seed.campaign.api;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * routesphere's {@code campaign_task} row for ONE task, immutable — each step of its life returns the next row.
 * The voice columns carry a view's lifecycle ({@code START_TIME_MILLIS} = claimed, {@code ANSWER_TIME_MILLIS} = shown,
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
