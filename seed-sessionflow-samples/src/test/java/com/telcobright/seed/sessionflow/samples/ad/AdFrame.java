package com.telcobright.seed.sessionflow.samples.ad;

/**
 * One road of the phone as the HTTP adapter hands it to the channel (the channel's inbound type): the road's name and what it carried.
 * The adapter turns each HTTP request into one frame with the session id (the view's key).
 */
public record AdFrame(String road, String listen, String mac, String msisdn, String zone, String site, String app, String gateway,
                      int watchedSeconds, String why) {

    public static AdFrame start(String listen, String mac, String msisdn, String zone, String site, String app, String gateway) {
        return new AdFrame("ad-sessions", listen, mac, msisdn, zone, site, app, gateway, 0, null);
    }
    public static AdFrame shown()                    { return road("shown", 0, null); }
    public static AdFrame progress(int watchedSec)   { return road("progress", watchedSec, null); }
    public static AdFrame viewed(int watchedSec)     { return road("viewed", watchedSec, null); }
    public static AdFrame abandoned(String why)      { return road("abandoned", 0, why); }
    public static AdFrame credited()                 { return road("credited", 0, null); }

    private static AdFrame road(String road, int watchedSec, String why) {
        return new AdFrame(road, null, null, null, null, null, null, null, watchedSec, why);
    }
}
