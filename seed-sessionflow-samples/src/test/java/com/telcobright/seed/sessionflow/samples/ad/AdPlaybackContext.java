package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.statewalk.session.events.SignalingFailed;

import java.util.ArrayList;
import java.util.List;

/** Everything one view's machine knows — cleared by the registry when the view ends. Jackson-friendly (persistence). */
public final class AdPlaybackContext {
    public String sessionId;
    public AdCreative creative;
    public long offeredAtMs;
    public long shownAtMs;
    public long viewedAtMs;
    public int watchedSeconds;
    public int beacons;
    /** The failure before the answer, published when the view ends unviewed. */
    public SignalingFailed failed;
    /** The states this machine visited, in order. */
    public final List<String> road = new ArrayList<>();
}
