package com.telcobright.seed.campaign.api;

/** {@code campaign.CAMPAIGN_TYPE}: what one task of the campaign is. */
public enum CampaignKind {
    SMS, VOICE, AD;

    public static CampaignKind of(String column) {
        if (column == null || column.isBlank()) return SMS;      // routesphere's default: a null type is an SMS campaign
        return valueOf(column.trim().toUpperCase());
    }
}
