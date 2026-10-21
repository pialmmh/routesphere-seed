package com.telcobright.seed.sessionflow.samples.wifi;

/**
 * One event of the WiFi gateway as the gateway adapter hands it to the channel (the inbound type): its kind and what it carried. The
 * adapter files every event of a device's session under the session id (the gateway's accounting session id).
 */
public record WifiFrame(String kind, String gateway, int svlan, String mac, String msisdn, String zone, String site, String policy,
                        String cause, long bytes, long seconds) {

    public static WifiFrame login(String gateway, int svlan, String mac, String msisdn, String zone, String site) {
        return new WifiFrame("login", gateway, svlan, mac, msisdn, zone, site, null, null, 0, 0);
    }
    public static WifiFrame opened(String policy)               { return of("opened", policy, null, 0, 0); }
    public static WifiFrame refused(String cause)               { return of("refused", null, cause, 0, 0); }
    public static WifiFrame usage(long bytes, long seconds)     { return of("usage", null, null, bytes, seconds); }
    public static WifiFrame left(String cause)                  { return of("left", null, cause, 0, 0); }
    public static WifiFrame closed(long bytes, long seconds)    { return of("closed", null, null, bytes, seconds); }

    private static WifiFrame of(String kind, String policy, String cause, long bytes, long seconds) {
        return new WifiFrame(kind, null, 0, null, null, null, null, policy, cause, bytes, seconds);
    }
}
