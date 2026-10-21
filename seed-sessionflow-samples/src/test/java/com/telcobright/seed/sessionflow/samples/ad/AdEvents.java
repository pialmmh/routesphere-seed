package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.statewalk.event.StatemachineEvent;

/** The AdPlayback machine's events, one per road of the phone (decoded by {@link AdFrames}): records with components. */
public final class AdEvents {

    private AdEvents() {}

    /**
     * {@code POST ad-sessions} — the first event of a view: the registry takes the kit for it. {@code listen} is the local address the
     * HTTP request arrived on (the ingress identity the root is found by).
     */
    public record AdRequest(String sessionId, String listen, String mac, String msisdn, String zone, String site, String app,
                            String gateway, long atMs) implements StatemachineEvent {
        @Override public boolean isFirst() { return true; }
    }
    /** {@code /shown}: the creative is on the screen. */
    public record AdShown(long atMs) implements StatemachineEvent {}
    /** {@code /progress}: a beacon while it plays. */
    public record AdProgress(long atMs, int watchedSeconds) implements StatemachineEvent {}
    /** {@code /viewed}: the whole view ran — the ad's answer. */
    public record AdViewed(long atMs, int watchedSeconds) implements StatemachineEvent {}
    /** {@code /abandoned}: the phone left before the view completed. */
    public record AdAbandoned(long atMs, String why) implements StatemachineEvent {}
    /** {@code /credited}: the subscriber took the reward (the free minutes) — the ad's hangup. */
    public record AdCredited(long atMs) implements StatemachineEvent {}
}
