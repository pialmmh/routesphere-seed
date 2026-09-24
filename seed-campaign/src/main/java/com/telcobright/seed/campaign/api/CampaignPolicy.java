package com.telcobright.seed.campaign.api;

import java.util.List;

/**
 * routesphere's {@code policy} as a campaign sees it: the TimeBands (when), plus the one ad-only knob, the per-device
 * frequency cap ({@code campaign.FIELD1}). The retry parts of the SMS policy (RetryInterval, RetryCauseCode) belong to
 * the SMS runner and are not modelled here — a view is never retried.
 *
 * @param frequencyCapPerDevicePerDay null or 0 = no cap
 */
public record CampaignPolicy(List<TimeBand> timeBands, Integer frequencyCapPerDevicePerDay) {

    public static final CampaignPolicy ALWAYS = new CampaignPolicy(List.of(), null);

    public CampaignPolicy {
        timeBands = List.copyOf(timeBands);
    }

    public boolean capped() { return frequencyCapPerDevicePerDay != null && frequencyCapPerDevicePerDay > 0; }
}
