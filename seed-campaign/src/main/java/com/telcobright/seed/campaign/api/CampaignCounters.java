package com.telcobright.seed.campaign.api;

/**
 * What the service knows of one campaign right now: the store's counters at load time plus what this process did since.
 *
 * @param sent    completed (store + since load)
 * @param failed  failed (store + since load)
 * @param pending live in this process
 */
public record CampaignCounters(int campaignId, String status, int total, int sent, int failed, int pending, int servedToday) {

    public boolean quotaLeft() { return total <= 0 || sent + pending < total; }
}
