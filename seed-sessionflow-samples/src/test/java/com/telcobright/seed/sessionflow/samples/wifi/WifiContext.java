package com.telcobright.seed.sessionflow.samples.wifi;

import com.telcobright.seed.sessionflow.api.RoutesphereContext;

/** One WiFi session on the base: the base's context plus the device's own facts (persisted: words and numbers only). */
public final class WifiContext extends RoutesphereContext {
    public volatile String msisdn;
    public volatile String mac;
    public volatile String zone;
}
