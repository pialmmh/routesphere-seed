package com.telcobright.seed.sessionflow.samples.wifi;

/** A command to the gateway, or the captive portal's answer to the device (the channel's outbound type). */
public record WifiOut(String to, String line) {
    public static WifiOut open(String mac, String policy, String gateway) { return new WifiOut("gateway", "open " + mac + " policy " + policy + " on " + gateway); }
    public static WifiOut close(String mac, String cause)                 { return new WifiOut("gateway", "close " + mac + " " + cause); }
    public static WifiOut deny(String mac, String cause)                  { return new WifiOut("portal", "deny " + mac + " " + cause); }
    @Override public String toString() { return to + ":" + line; }
}
