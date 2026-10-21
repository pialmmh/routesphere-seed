package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.seed.sessionflow.samples.ad.AdEvents.*;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.event.StatemachineEvent;

/** The decoder: a road of the phone becomes the AdPlayback machine's event, delivered to it by name; the registry knows no HTTP word. */
public final class AdFrames {

    private AdFrames() {}

    public static Delivery decode(String sessionId, AdFrame f) {
        long now = System.currentTimeMillis();
        StatemachineEvent event = switch (f.road()) {
            case "ad-sessions" -> new AdRequest(sessionId, f.listen(), f.mac(), f.msisdn(), f.zone(), f.site(), f.app(), f.gateway(), now);
            case "shown" -> new AdShown(now);
            case "progress" -> new AdProgress(now, f.watchedSeconds());
            case "viewed" -> new AdViewed(now, f.watchedSeconds());
            case "abandoned" -> new AdAbandoned(now, f.why());
            case "credited" -> new AdCredited(now);
            default -> null;                                       // a road the machine does not know: ignored
        };
        return event == null ? null : Delivery.to(AdPlayback.TYPE, event);
    }
}
