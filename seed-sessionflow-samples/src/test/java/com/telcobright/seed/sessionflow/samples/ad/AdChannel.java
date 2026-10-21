package com.telcobright.seed.sessionflow.samples.ad;

import com.telcobright.statewalk.channel.TestChannel;

/** The fake HTTP adapter of the phone's roads: records every answer the machine sends; the test injects the roads as frames. */
public final class AdChannel extends TestChannel<AdOut, AdFrame> {
    public AdChannel() { super("ad-http"); }
}
