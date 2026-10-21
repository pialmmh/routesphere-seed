package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.statewalk.channel.TestChannel;

/** The fake client door + SMPP bind: records every PDU and answer the machine sends; the test injects the frames. */
public final class SmsChannel extends TestChannel<SmsOut, SmsFrame> {
    public SmsChannel() { super("sms"); }
}
