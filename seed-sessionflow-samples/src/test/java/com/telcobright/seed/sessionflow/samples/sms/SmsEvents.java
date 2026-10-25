package com.telcobright.seed.sessionflow.samples.sms;

import com.telcobright.statewalk.event.StatemachineEvent;

/** The SmsSubmit machine's events, decoded from the frames of the client side and of the SMSC ({@link SmsFrames}). */
public final class SmsEvents {

    private SmsEvents() {}

    /**
     * The client's message — the first event of an SMS: the registry takes the kit for it. The ingress is {@code listen <ip>:<port>} for
     * the HTTP door or {@code topic <name>} for the Kafka door (its kind and match); {@code user} names the entry partner.
     */
    public record SmsRequest(String sessionId, String ingressKind, String ingressMatch, String user, String password, Integer partnerId,
                             String sender, String receiver, String text, long atMs) implements StatemachineEvent {
        @Override public boolean isFirst() { return true; }

        /** P1-O · never the password. */
        @Override public String toString() {
            return "SmsRequest[" + sessionId + " " + ingressKind + " " + ingressMatch + " user=" + user + (partnerId == null ? "" : " partner=" + partnerId) + "]";
        }
    }
    /** {@code submit_sm_resp}: status 0 = accepted with the SMSC's message id, else the SMSC refused (its command_status). */
    public record SubmitResp(int status, String messageId, long atMs) implements StatemachineEvent {}
    /** {@code deliver_sm} carrying the delivery receipt: its {@code stat} (DELIVRD, UNDELIV, EXPIRED, REJECTD, …). */
    public record DeliveryReport(String stat, String err, long atMs) implements StatemachineEvent {}

    /** The cause word of an SMPP command_status (the re-route table reads it). */
    public static String submitCause(int status) {
        return switch (status) {
            case 0x08 -> "SMSC_SYSTEM_ERROR";
            case 0x0B -> "INVALID_DESTINATION";
            case 0x14 -> "QUEUE_FULL";
            case 0x58 -> "THROTTLED";
            default -> "SUBMIT_REFUSED_" + status;
        };
    }

    /** The cause word of a delivery receipt's {@code stat}: the end cause of a submitted message. */
    public static String deliveryCause(String stat) {
        return switch (stat) {
            case "DELIVRD" -> "DELIVERED";
            case "UNDELIV" -> "UNDELIVERED";
            case "EXPIRED" -> "EXPIRED";
            case "REJECTD" -> "REJECTED";
            default -> "DLR_" + stat;
        };
    }
}
