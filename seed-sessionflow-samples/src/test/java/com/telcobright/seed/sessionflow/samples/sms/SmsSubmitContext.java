package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.statewalk.session.events.SignalingFailed;

import java.util.ArrayList;
import java.util.List;

/** Everything one message's machine knows — cleared by the registry when the message ends. Jackson-friendly (persistence). */
public final class SmsSubmitContext {
    public String sessionId;
    public String sender;
    public String receiver;
    public int segments;
    public Smsc smsc;
    public String messageId;
    public int submits;
    public SignalingFailed failed;
    /** The states this machine visited, in order. */
    public final List<String> road = new ArrayList<>();
}
