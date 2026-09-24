package com.telcobright.seed.campaign.api;

/**
 * One creative of a campaign ({@code campaign_creative}): a pointer, never the bytes. {@code mediaRef} is the media
 * server's id of the stream; {@code durationSec} is how long the view must last (0 = the campaign's default).
 */
public record Creative(String id, MediaKind kind, String mediaRef, int durationSec, String clickUrl, String caption) {

    public static Creative text(String id, String caption) {
        return new Creative(id, MediaKind.TEXT, null, 0, null, caption);
    }

    public static Creative stream(String id, MediaKind kind, String mediaRef, int durationSec) {
        return new Creative(id, kind, mediaRef, durationSec, null, null);
    }
}
