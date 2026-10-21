package com.telcobright.seed.sessionflow.samples.voice;

/** An ESL command (the channel's outbound type), as {@code bgapi} would send it. */
public record EslOut(String line) {
    public static EslOut setvar(String uuid, String var, String value) { return new EslOut("uuid_setvar " + uuid + " " + var + " " + value); }
    public static EslOut transfer(String uuid, String dest, String context) { return new EslOut("uuid_transfer " + uuid + " " + dest + " XML " + context); }
    public static EslOut kill(String uuid, String cause)             { return new EslOut("uuid_kill " + uuid + " " + cause); }
    @Override public String toString() { return line; }
}
