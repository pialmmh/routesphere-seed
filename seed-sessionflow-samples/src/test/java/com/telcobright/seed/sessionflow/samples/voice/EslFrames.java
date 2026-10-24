package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.seed.sessionflow.samples.voice.EslEvents.*;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.event.StatemachineEvent;

/** The decoder: a FreeSWITCH event becomes the EslCall machine's event, delivered to it by name; the registry knows no ESL word. */
public final class EslFrames {

    private EslFrames() {}

    /** @param sessionId the A-leg's uuid — the session the adapter filed the event under */
    public static Delivery decode(String sessionId, EslFrame f) {
        long now = System.currentTimeMillis();
        StatemachineEvent event = switch (f.event()) {
            case "CHANNEL_PARK" -> new ChannelPark(sessionId, f.esl(), f.caller(), f.called(), f.sourceIp(), f.sipAccount(), now, f.sourcePort());
            case "CHANNEL_PROGRESS" -> new ChannelProgress(false, null, now);
            case "CHANNEL_PROGRESS_MEDIA" -> new ChannelProgress(true, f.sdp(), now);
            case "CHANNEL_ANSWER" -> new ChannelAnswer(f.otherUuid(), f.codec(), f.sdp(), now);
            case "CHANNEL_BRIDGE" -> new ChannelBridge(f.otherUuid(), now);
            case "CHANNEL_EXECUTE_COMPLETE" -> f.hangupCause() == null ? null : new BridgeFailed(f.hangupCause(), EslEvents.q850(f.hangupCause()), now);
            case "CHANNEL_HANGUP" -> new ChannelHangup(sessionId.equals(f.uuid()), f.hangupCause(), now);
            case "CHANNEL_HANGUP_COMPLETE" -> new ChannelHangupComplete(f.hangupCause(), now);
            default -> null;                                           // heartbeats, DTMF, …: nothing for the machine
        };
        return event == null ? null : Delivery.to(EslCall.TYPE, event);
    }
}
