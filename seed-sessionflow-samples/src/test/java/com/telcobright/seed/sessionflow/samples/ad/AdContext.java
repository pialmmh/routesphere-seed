package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.seed.sessionflow.api.RoutesphereContext;

/** One ad view on the base: the base's context plus the view's own facts (persisted: words and numbers only). */
public final class AdContext extends RoutesphereContext {
    /** The ad rule the zone matched — the view's "called number". */
    public volatile String ruleCode;
    /** The creative reached the phone (the hand-over: AdPlayback's OFFERED). */
    public volatile boolean handedOver;
    /** The creative was on the screen (AdPlayback's SHOWING). */
    public volatile boolean shown;
    /** The seconds the view's answer says were watched. */
    public volatile int watchedSeconds;
}
