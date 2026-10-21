package com.telcobright.seed.sessionflow.samples.sms;

/** A PDU to the SMSC, or the answer to the client (the channel's outbound type). */
public record SmsOut(String to, String line) {
    public static SmsOut submit(Smsc smsc, String sender, String receiver, int segments) {
        return new SmsOut("smsc", "submit_sm " + smsc.smscId() + " " + sender + ">" + receiver + " segments=" + segments);
    }
    public static SmsOut cancel(String messageId)        { return new SmsOut("smsc", "cancel_sm " + messageId); }
    public static SmsOut deliverResp()                   { return new SmsOut("smsc", "deliver_sm_resp"); }
    public static SmsOut toClient(String line)           { return new SmsOut("client", line); }
    @Override public String toString() { return to + ":" + line; }
}
