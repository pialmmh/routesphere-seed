package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.seed.sessionflow.samples.sms.SmsEvents.*;
import com.telcobright.statewalk.channel.Delivery;
import com.telcobright.statewalk.event.StatemachineEvent;

/** The decoder: a client request or an SMPP PDU becomes the SmsSubmit machine's event, delivered to it by name. */
public final class SmsFrames {

    private SmsFrames() {}

    public static Delivery decode(String sessionId, SmsFrame f) {
        long now = System.currentTimeMillis();
        StatemachineEvent event = switch (f.kind()) {
            case "request" -> new SmsRequest(sessionId, f.ingressKind(), f.ingressMatch(), f.user(), f.sender(), f.receiver(), f.text(), now);
            case "submit_sm_resp" -> new SubmitResp(f.status(), f.messageId(), now);
            case "deliver_sm" -> new DeliveryReport(f.stat(), f.err(), now);
            default -> null;                                               // enquire_link and the like: nothing for the machine
        };
        return event == null ? null : Delivery.to(SmsSubmit.TYPE, event);
    }
}
