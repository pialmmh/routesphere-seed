package com.telcobright.seed.sessionflow.samples.sms;

/**
 * One frame of the SMS lane as the adapters hand it to the channel (the inbound type): the client's request (HTTP body or Kafka record)
 * or an SMPP PDU from the SMSC, filed under the session id (the minted task id; the SMSC's message id is mapped back to it). P1-O: the HTTP
 * request carries the client's route credentials (user name + password: the aggregator API's); the Kafka record the partner its campaign
 * names. The password is never in this record's text.
 */
public record SmsFrame(String kind, String ingressKind, String ingressMatch, String user, String password, Integer partnerId, String sender,
                       String receiver, String text, int status, String messageId, String stat, String err) {

    /** The HTTP door: the client's route credentials and the message. */
    public static SmsFrame http(String listen, String user, String password, String sender, String receiver, String text) {
        return new SmsFrame("request", "listen", listen, user, password, null, sender, receiver, text, 0, null, null, null);
    }
    /** The Kafka door: a campaign's message — the campaign names the partner that pays. */
    public static SmsFrame kafka(String topic, int campaignPartnerId, String sender, String receiver, String text) {
        return new SmsFrame("request", "topic", topic, null, null, campaignPartnerId, sender, receiver, text, 0, null, null, null);
    }
    public static SmsFrame submitOk(String messageId)  { return pdu("submit_sm_resp", 0, messageId, null, null); }
    public static SmsFrame submitError(int status)     { return pdu("submit_sm_resp", status, null, null, null); }
    public static SmsFrame dlr(String stat)            { return pdu("deliver_sm", 0, null, stat, "000"); }

    private static SmsFrame pdu(String kind, int status, String messageId, String stat, String err) {
        return new SmsFrame(kind, null, null, null, null, null, null, null, null, status, messageId, stat, err);
    }

    @Override
    public String toString() {
        return "SmsFrame[" + kind + " " + ingressKind + " " + ingressMatch + " user=" + user + (password == null ? "" : " password=***")
            + (partnerId == null ? "" : " partner=" + partnerId) + " " + sender + ">" + receiver + "]";
    }
}
