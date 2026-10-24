package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.statewalk.event.StatemachineEvent;

/** The EslCall machine's events, decoded from FreeSWITCH event frames ({@link EslFrames}): records with components. */
public final class EslEvents {

    private EslEvents() {}

    /**
     * {@code CHANNEL_PARK} of the A-leg — the first event of a call: the registry takes the kit for it. {@code esl} is the FreeSWITCH
     * connection ({@code host:port}) the event came from — the ingress identity the root is found by; the adapter carries it.
     */
    public record ChannelPark(String uuid, String esl, String caller, String called, String sourceIp, String sipAccount, long atMs, int sourcePort)
        implements StatemachineEvent {
        @Override public boolean isFirst() { return true; }
    }
    /** {@code CHANNEL_PROGRESS} (180) or {@code CHANNEL_PROGRESS_MEDIA} (183, early media). */
    public record ChannelProgress(boolean media, String sdp, long atMs) implements StatemachineEvent {}
    /** {@code CHANNEL_ANSWER}: the far end answered. */
    public record ChannelAnswer(String otherParty, String codec, String sdp, long atMs) implements StatemachineEvent {}
    /** {@code CHANNEL_BRIDGE}: the two legs are bridged — the other leg's uuid (C22). */
    public record ChannelBridge(String otherUuid, long atMs) implements StatemachineEvent {}
    /** {@code CHANNEL_EXECUTE_COMPLETE} of a {@code bridge} that failed: the attempt's hangup cause; the A-leg stays parked. */
    public record BridgeFailed(String cause, int q850, long atMs) implements StatemachineEvent {}
    /** {@code CHANNEL_HANGUP} of either leg: {@code aLeg} = the caller's leg. */
    public record ChannelHangup(boolean aLeg, String cause, long atMs) implements StatemachineEvent {}
    /** {@code CHANNEL_HANGUP_COMPLETE} of the A-leg: the call is gone from FreeSWITCH. */
    public record ChannelHangupComplete(String cause, long atMs) implements StatemachineEvent {}

    /** The Q.850 code of a FreeSWITCH hangup cause (the CDR's vocabulary; the re-route table reads the cause). */
    public static int q850(String cause) {
        return switch (cause) {
            case "NO_ROUTE_DESTINATION" -> 3;
            case "NORMAL_CLEARING" -> 16;
            case "USER_BUSY" -> 17;
            case "NO_USER_RESPONSE" -> 18;
            case "NO_ANSWER" -> 19;
            case "SUBSCRIBER_ABSENT" -> 20;
            case "CALL_REJECTED" -> 21;
            case "NORMAL_TEMPORARY_FAILURE" -> 41;
            case "SWITCH_CONGESTION" -> 42;
            case "RECOVERY_ON_TIMER_EXPIRE" -> 102;
            case "ORIGINATOR_CANCEL" -> 487;
            default -> 0;
        };
    }

    /** The hangup cause the parked leg is killed with when the supervisor refuses the call with a routesphere cause. */
    public static String hangupCauseFor(String routesphereCause) {
        return switch (routesphereCause) {
            case "NO_ROUTE" -> "NO_ROUTE_DESTINATION";
            case "INSUFFICIENT_BALANCE", "NO_BALANCE_INT_OUT", "BALANCE_EXHAUSTED", "PARTNER_NOT_FOUND", "PARTNER_DEACTIVATED",
                 "DIGIT_FILTER_DENIED", "CHANNEL_LIMIT_REACHED", "RETAIL_CHANNEL_LIMIT_REACHED", "INVALID_DID", "NO_TENANT" -> "CALL_REJECTED";
            default -> q850(routesphereCause) > 0 ? routesphereCause : "NORMAL_TEMPORARY_FAILURE";
        };
    }
}
