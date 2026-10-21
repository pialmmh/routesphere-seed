package com.telcobright.seed.sessionflow.samples.scene;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.samples.bed.Crash;
import com.telcobright.seed.sessionflow.spi.CdrSink;

import java.util.List;

/** One node's CDR road (P1 T10): its records reach the sink until the node dies; a dead process publishes nothing. */
public final class CrashingCdrSink implements CdrSink {

    private final CdrSink sink;
    private final Crash crash;

    public CrashingCdrSink(CdrSink sink, Crash crash) {
        this.sink = sink;
        this.crash = crash;
    }

    @Override public void publish(String callId, List<CdrEvent> tiers) { if (!crash.isDead()) sink.publish(callId, tiers); }

    @Override public void publish(String root, String callId, List<CdrEvent> tiers) { if (!crash.isDead()) sink.publish(root, callId, tiers); }
}
