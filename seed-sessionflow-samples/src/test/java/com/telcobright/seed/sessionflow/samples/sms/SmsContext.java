package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.seed.sessionflow.api.RoutesphereContext;

/** One SMS on the base: the base's context plus the message's own facts (persisted: words and numbers only). */
public final class SmsContext extends RoutesphereContext {
    public volatile String user;
    /** GSM-7 segments of 160 characters: the unit the tiers are rated in. */
    public volatile int segments;
}
