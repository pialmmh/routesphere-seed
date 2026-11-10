package com.telcobright.seed.memledgerremote.api;

/**
 * ARCH-0118 · What a server says it does, one word each in {@code HealthReply.capabilities}; the client and the server name them from
 * here, one spelling. A server that says neither is an older one: its client keeps the client-side ghost rule and sends no expectations
 * (one writer per instance).
 */
public final class Capabilities {

    /** The server takes out the ghost reserves its own reload brings back, under a gate no write passes: the client must not. */
    public static final String GHOSTS = "ghosts";

    /** The server checks the rows a batch read ({@code ApplyRequest.expects}) under the batch's row locks: one that differs = STALE. */
    public static final String EXPECT = "expect";

    private Capabilities() {}
}
