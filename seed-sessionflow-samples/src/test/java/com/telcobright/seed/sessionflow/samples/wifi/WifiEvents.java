package com.telcobright.seed.sessionflow.samples.wifi;

import com.telcobright.statewalk.event.StatemachineEvent;

/** The WifiGateway machine's events, decoded from the gateway's frames ({@link WifiFrames}): records with components. */
public final class WifiEvents {

    private WifiEvents() {}

    /**
     * The device passed the captive login on a gateway — the first event of a WiFi session: the registry takes the kit for it.
     * {@code gateway} + {@code svlan} are the ingress identity ({@code vlan <gateway>:<svlan>}) the root is found by; the MSISDN is the
     * subscriber the session is for.
     */
    public record DeviceLogin(String sessionId, String gateway, int svlan, String mac, String msisdn, String zone, String site, long atMs)
        implements StatemachineEvent {
        @Override public boolean isFirst() { return true; }
    }
    /** The gateway opened the device's gate (RADIUS Access-Accept took effect / the policy is installed). */
    public record GateOpened(String policy, long atMs) implements StatemachineEvent {}
    /** The gateway refused to open it (the policy is unknown, the device is blocked). */
    public record GateRefused(String cause, long atMs) implements StatemachineEvent {}
    /** An interim accounting report: the bytes and seconds so far. */
    public record UsageReport(long bytes, long seconds, long atMs) implements StatemachineEvent {}
    /** The device left (logout, lost carrier, idle on the gateway) — the subscriber's hangup. */
    public record DeviceLeft(String cause, long atMs) implements StatemachineEvent {}
    /** The gateway closed the gate (accounting stop): the session's final usage. */
    public record GateClosed(long bytes, long seconds, long atMs) implements StatemachineEvent {}
}
