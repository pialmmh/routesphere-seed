package com.telcobright.seed.sessionflow.samples.wifi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;

/**
 * A WiFi session's facts, from the captive login, as the WifiGateway machine stamps them on {@code SessionStart}: the ingress identity
 * ({@code vlan <gateway>:<svlan>}) and the device's own facts — the subscriber (MSISDN) is the payer [assumes D5, sample only].
 */
public record WifiFacts(String sessionId, long atMs, String gateway, int svlan, String mac, String msisdn, String zone, String site)
    implements RoutesphereFacts {

    public static WifiFacts of(WifiEvents.DeviceLogin l) {
        return new WifiFacts(l.sessionId(), l.atMs(), l.gateway(), l.svlan(), l.mac(), l.msisdn(), l.zone(), l.site());
    }

    /** The ingress rule's match: {@code <gateway>:<svlan>}. */
    @JsonIgnore public String vlan() { return gateway + ":" + svlan; }

    /** The gateway and its S-VLAN: {@code vlan <gateway>:<svlan>} — the base finds the ROOT by it (step a). */
    @JsonIgnore @Override public Ingress ingress() { return gateway == null ? null : new Ingress(Ingress.VLAN, vlan()); }
}
