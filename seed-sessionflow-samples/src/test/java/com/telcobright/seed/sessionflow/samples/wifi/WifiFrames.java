package com.telcobright.seed.sessionflow.samples.wifi;

import com.telcobright.seed.sessionflow.samples.wifi.WifiEvents.*;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.event.StatemachineEvent;

/** The decoder: a gateway event becomes the WifiGateway machine's event, delivered to it by name; the registry knows no RADIUS word. */
public final class WifiFrames {

    private WifiFrames() {}

    public static Delivery decode(String sessionId, WifiFrame f) {
        long now = System.currentTimeMillis();
        StatemachineEvent event = switch (f.kind()) {
            case "login" -> new DeviceLogin(sessionId, f.gateway(), f.svlan(), f.mac(), f.msisdn(), f.zone(), f.site(), now);
            case "opened" -> new GateOpened(f.policy(), now);
            case "refused" -> new GateRefused(f.cause(), now);
            case "usage" -> new UsageReport(f.bytes(), f.seconds(), now);
            case "left" -> new DeviceLeft(f.cause(), now);
            case "closed" -> new GateClosed(f.bytes(), f.seconds(), now);
            default -> null;
        };
        return event == null ? null : Delivery.to(WifiGateway.TYPE, event);
    }
}
