package com.telcobright.seed.campaign.api;

/** What a creative is. Every kind is stored and streamed the same way (an image is looped into a clip at ingest). */
public enum MediaKind {
    IMAGE, VIDEO, AUDIO, TEXT;

    public static MediaKind of(String column) {
        if (column == null || column.isBlank()) return TEXT;
        return valueOf(column.trim().toUpperCase());
    }
}
