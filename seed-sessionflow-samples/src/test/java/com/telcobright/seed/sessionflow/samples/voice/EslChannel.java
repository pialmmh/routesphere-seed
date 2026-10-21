package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.statewalk.channel.TestChannel;

/** The fake ESL connection: records every command the machine sends; the test injects FreeSWITCH events as frames. */
public final class EslChannel extends TestChannel<EslOut, EslFrame> {
    public EslChannel() { super("esl"); }
}
