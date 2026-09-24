package com.telcobright.seed.campaign.internal;

import com.telcobright.seed.campaign.api.Campaign;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * routesphere's {@code CampaignRunnerJob.isRunnable}, plus the schedule window: the reason a campaign may NOT run now,
 * or empty. The order is the SMS runner's: status, expiry, then the policy.
 */
public final class RunnableRule {

    private RunnableRule() {}

    public static Optional<String> closed(Campaign c, Instant now, ZoneId zone) {
        if (c.terminal()) return Optional.of("status:" + c.status());
        if (c.expireAt() != null && !now.isBefore(c.expireAt())) return Optional.of("expired");
        if (c.scheduleStart() != null && now.isBefore(c.scheduleStart())) return Optional.of("not-started");
        if (c.scheduleEnd() != null && !now.isBefore(c.scheduleEnd())) return Optional.of("schedule-ended");
        LocalDateTime local = LocalDateTime.ofInstant(now, zone);
        if (!TimeBandRule.open(c.policy().timeBands(), local)) return Optional.of("outside-time-band");
        return Optional.empty();
    }
}
