package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.statewalk.session.SessionFacts;

/**
 * A call's facts, from its {@code CHANNEL_PARK}, as the EslCall machine stamps them on {@code SessionStart}: the ingress identity
 * ({@code esl <host>:<port>}, the FreeSWITCH connection) and the call's own facts — the caller, the called number (the DID), the source
 * address and the SIP account the entry partner is found by (C1).
 */
public record EslFacts(String sessionId, long atMs, String esl, String caller, String called, String sourceIp, String sipAccount)
    implements SessionFacts {

    public static EslFacts of(EslEvents.ChannelPark p) {
        return new EslFacts(p.uuid(), p.atMs(), p.esl(), p.caller(), p.called(), p.sourceIp(), p.sipAccount());
    }
}
