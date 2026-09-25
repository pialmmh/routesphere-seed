package com.telcobright.seed.campaign.api;

/**
 * One creative of a campaign: a pointer, never the bytes. {@code mediaRef} is the media server's id of the stream;
 * {@code durationSec} is how long the view must last (0 = the campaign's default).
 *
 * <p>Since 2026-09-25 (the ad model, owner-locked) a creative may be a CONTENT another partner owns and the campaign
 * merely carries: {@code partnerId} is that owner (0 = the campaign's own partner) and {@code sharePercent} its share
 * of the campaign's draws (0 = an equal share). The legacy {@code campaign_creative} rows read as owner 0, share 0.
 */
public record Creative(String id, MediaKind kind, String mediaRef, int durationSec, String clickUrl, String caption,
                       int partnerId, double sharePercent) {
    public Creative(String id, MediaKind kind, String mediaRef, int durationSec, String clickUrl, String caption) {
        this(id, kind, mediaRef, durationSec, clickUrl, caption, 0, 0);
    }
    public static Creative text(String id, String caption) {
        return new Creative(id, MediaKind.TEXT, null, 0, null, caption);
    }
    public static Creative stream(String id, MediaKind kind, String mediaRef, int durationSec) {
        return new Creative(id, kind, mediaRef, durationSec, null, null);
    }
    /** The same creative as a content of {@code partnerId} with {@code sharePercent} of the campaign's draws. */
    public Creative ownedBy(int partnerId, double sharePercent) {
        return new Creative(id, kind, mediaRef, durationSec, clickUrl, caption, partnerId, sharePercent);
    }
    /** The partner charged for a view of this creative: its owner, else the campaign's. */
    public int payerOr(int campaignPartnerId) { return partnerId > 0 ? partnerId : campaignPartnerId; }
}
