package com.telcobright.seed.callflow.testkit;

import com.telcobright.seed.callflow.api.CdrEvent;
import com.telcobright.seed.callflow.internal.CdrJson;
import com.telcobright.seed.callflow.spi.CdrSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A CDR sink that keeps what it was given, for the tests of every product on the call flow. */
public final class RecordingCdrSink implements CdrSink {

    /** One call as it was published: its id (the Kafka key) and its tier records, the leaf first. */
    public record Published(String callId, List<CdrEvent> tiers) {
        /** Exactly the Kafka value. */
        public String json() { return CdrJson.ofCall(tiers); }
    }

    private final List<Published> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String callId, List<CdrEvent> tiers) { published.add(new Published(callId, List.copyOf(tiers))); }

    public List<Published> published() { return List.copyOf(published); }

    /** The messages of one call: exactly one for a call that ended, none for a call refused at the door. */
    public List<Published> of(String callId) { return published.stream().filter(p -> p.callId().equals(callId)).toList(); }

    public int count() { return published.size(); }
}
