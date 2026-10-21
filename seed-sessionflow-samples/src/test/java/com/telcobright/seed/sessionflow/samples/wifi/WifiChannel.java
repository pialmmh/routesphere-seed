package com.telcobright.seed.sessionflow.samples.wifi;

import com.telcobright.statewalk.channel.TestChannel;

/** The fake gateway adapter: records every command the machine sends; the test injects the gateway's events as frames. */
public final class WifiChannel extends TestChannel<WifiOut, WifiFrame> {
    public WifiChannel() { super("wifi-gateway"); }
}
