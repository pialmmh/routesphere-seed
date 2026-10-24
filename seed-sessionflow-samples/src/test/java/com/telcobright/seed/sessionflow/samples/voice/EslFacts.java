package com.telcobright.seed.sessionflow.samples.voice;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.telcobright.seed.sessionflow.api.Ingress;
import com.telcobright.seed.sessionflow.api.RoutesphereFacts;

/**
 * A call's facts, from its {@code CHANNEL_PARK}, as the EslCall machine stamps them on {@code SessionStart}: the ingress identity
 * ({@code esl <host>:<port>}, the FreeSWITCH connection) and the call's own facts — the caller, the called number (the DID), the source
 * address (and, P1-O, its port: the route at {@code ip:port} first) and the SIP account the entry partner is found by (C1).
 */
public record EslFacts(String sessionId, long atMs, String esl, String caller, String called, String sourceIp, String sipAccount, int sourcePort)
    implements RoutesphereFacts {

    /** Facts whose source port was not captured (0). */
    public EslFacts(String sessionId, long atMs, String esl, String caller, String called, String sourceIp, String sipAccount) {
        this(sessionId, atMs, esl, caller, called, sourceIp, sipAccount, 0);
    }

    /** The FreeSWITCH connection the park came from: {@code esl <host>:<port>} — the base finds the ROOT by it (step a). */
    @JsonIgnore @Override public Ingress ingress() { return esl == null ? null : new Ingress(Ingress.ESL, esl); }

    public static EslFacts of(EslEvents.ChannelPark p) {
        return new EslFacts(p.uuid(), p.atMs(), p.esl(), p.caller(), p.called(), p.sourceIp(), p.sipAccount(), p.sourcePort());
    }
}
