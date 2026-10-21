package com.telcobright.seed.sessionflow.samples.voice;

import com.telcobright.seed.sessionflow.api.RoutesphereContext;

/** One voice call on the base: the base's context plus what the call keeps of its own (persisted: words and numbers only). */
public final class VoiceContext extends RoutesphereContext {
    /** C13: the call's recording was started at the answer (when the root enables it). */
    public volatile boolean recordingStarted;
}
