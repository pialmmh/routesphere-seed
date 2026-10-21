package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.statewalk.session.SessionFacts;

/**
 * A message's facts, from the client's request, as the SmsSubmit machine stamps them on {@code SessionStart}: the ingress identity
 * ({@code listen <ip>:<port>} or {@code topic <name>}) and the message's own facts — the user (the entry partner), the numbers, the
 * segments (a GSM-7 segment is 160 characters).
 */
public record SmsFacts(String sessionId, long atMs, String ingressKind, String ingressMatch, String user, String sender, String receiver,
                       int segments) implements SessionFacts {

    public static SmsFacts of(SmsEvents.SmsRequest r) {
        return new SmsFacts(r.sessionId(), r.atMs(), r.ingressKind(), r.ingressMatch(), r.user(), r.sender(), r.receiver(), segmentsOf(r.text()));
    }

    public static int segmentsOf(String text) { return text == null || text.isEmpty() ? 0 : (text.length() + 159) / 160; }
}
