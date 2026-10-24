package com.telcobright.seed.sessionflow.samples.voice;

/**
 * One FreeSWITCH event as the ESL adapter hands it to the channel (the inbound type): the event name and the headers the machine
 * reads. {@code uuid} is the leg the event is about; the adapter files every event of the call under the A-leg's uuid (the session id).
 * {@code esl} is the connection it came from ({@code host:port}) — today's shared listener drops it; the adapter must carry it (D2).
 */
public record EslFrame(String event, String uuid, String esl, String caller, String called, String sourceIp, String sipAccount,
                       String otherUuid, String hangupCause, String codec, String sdp, int sourcePort) {

    /** A park whose source port the adapter did not capture (0): the lane finds the route by the bare ip. */
    public static EslFrame park(String uuid, String esl, String caller, String called, String sourceIp, String sipAccount) {
        return park(uuid, esl, caller, called, sourceIp, 0, sipAccount);
    }

    /** P1-O · A park with the source port the leg arrived from ({@code variable_sip_network_port}): the route at {@code ip:port} first. */
    public static EslFrame park(String uuid, String esl, String caller, String called, String sourceIp, int sourcePort, String sipAccount) {
        return new EslFrame("CHANNEL_PARK", uuid, esl, caller, called, sourceIp, sipAccount, null, null, null, null, sourcePort);
    }
    public static EslFrame progress(String uuid)                { return of("CHANNEL_PROGRESS", uuid, null, null, null, null); }
    public static EslFrame progressMedia(String uuid, String sdp) { return of("CHANNEL_PROGRESS_MEDIA", uuid, null, null, null, sdp); }
    public static EslFrame answer(String uuid, String other, String codec) { return of("CHANNEL_ANSWER", uuid, other, null, codec, "v=0"); }
    public static EslFrame bridge(String uuid, String otherUuid) { return of("CHANNEL_BRIDGE", uuid, otherUuid, null, null, null); }
    /** A {@code bridge} application that completed without an answer: the originate disposition is the attempt's cause. */
    public static EslFrame bridgeFailed(String uuid, String cause) { return of("CHANNEL_EXECUTE_COMPLETE", uuid, null, cause, null, null); }
    public static EslFrame hangup(String legUuid, String cause)  { return of("CHANNEL_HANGUP", legUuid, null, cause, null, null); }
    public static EslFrame hangupComplete(String uuid, String cause) { return of("CHANNEL_HANGUP_COMPLETE", uuid, null, cause, null, null); }

    private static EslFrame of(String event, String uuid, String other, String cause, String codec, String sdp) {
        return new EslFrame(event, uuid, null, null, null, null, null, other, cause, codec, sdp, 0);
    }
}
