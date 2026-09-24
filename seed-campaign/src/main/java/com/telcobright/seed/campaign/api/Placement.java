package com.telcobright.seed.campaign.api;

/**
 * One way to serve a view: the campaign chosen and the creative it rotates to. {@code specificity} is why it ranked
 * where it did (the targeting score); {@code viewSeconds} is how long the view must last.
 */
public record Placement(Campaign campaign, Creative creative, int specificity) {

    public int viewSeconds() {
        return creative.durationSec() > 0 ? creative.durationSec() : campaign.defaultViewSeconds();
    }
}
