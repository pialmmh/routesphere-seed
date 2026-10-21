package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.statewalk.session.SessionFacts;

/**
 * A view's facts, from {@code POST ad-sessions}, as the AdPlayback machine stamps them on {@code SessionStart}: the ingress identity
 * ({@code listen <ip>:<port>}, the local address the request arrived on) and the device's own facts.
 */
public record AdFacts(String sessionId, long atMs, String listen, String mac, String msisdn, String zone, String site, String app,
                      String gateway) implements SessionFacts {

    public static AdFacts of(AdEvents.AdRequest r) {
        return new AdFacts(r.sessionId(), r.atMs(), r.listen(), r.mac(), r.msisdn(), r.zone(), r.site(), r.app(), r.gateway());
    }
}
