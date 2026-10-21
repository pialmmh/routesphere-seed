package com.telcobright.seed.sessionflow.testkit;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.internal.CdrJson;
import com.telcobright.seed.sessionflow.spi.CdrSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A CDR sink that keeps what it was given, for the tests of every product on the call flow.
 *
 * <p>Two views (P1 §L.1). {@link #published()} is every publish exactly as offered — a flow that publishes a session twice shows
 * there. {@link #records()} is what billing KEEPS: billing is idempotent on (tenant, channelCallUuid), so a tier record offered again
 * under the same key — a session ended a second time after a crash restored it from an older row — is dropped there and counted in
 * {@link #duplicates()}.
 */
public final class RecordingCdrSink implements CdrSink {

    /** One call as it was published: its id (the Kafka key) and its tier records, the leaf first. */
    public record Published(String callId, List<CdrEvent> tiers) {
        /** Exactly the Kafka value. */
        public String json() { return CdrJson.ofCall(tiers); }
    }

    private final List<Published> published = new CopyOnWriteArrayList<>();
    private final Map<String, CdrEvent> kept = new LinkedHashMap<>();
    private int duplicates;

    @Override
    public void publish(String callId, List<CdrEvent> tiers) {
        published.add(new Published(callId, List.copyOf(tiers)));
        keepOncePerKey(callId, tiers);
    }

    private synchronized void keepOncePerKey(String callId, List<CdrEvent> tiers) {
        for (CdrEvent tier : tiers) {
            if (kept.putIfAbsent(billingKeyOf(callId, tier), tier) != null) duplicates++;
        }
    }

    /** Billing's key of one tier record: its tenant and its channel call uuid (the call id when the record names none). */
    public static String billingKeyOf(String callId, CdrEvent tier) {
        String uuid = tier.channelCallUuid != null ? tier.channelCallUuid : callId;
        return tier.tenant + "|" + uuid;
    }

    public List<Published> published() { return List.copyOf(published); }

    /** The messages of one call: exactly one for a call that ended, none for a call refused at the door. */
    public List<Published> of(String callId) { return published.stream().filter(p -> p.callId().equals(callId)).toList(); }

    public int count() { return published.size(); }

    /** The tier records billing keeps — one per (tenant, channelCallUuid), the first offered, in the order offered. */
    public synchronized List<CdrEvent> records() { return new ArrayList<>(kept.values()); }

    /** The tier records offered again under a key already kept (dropped from {@link #records()}). */
    public synchronized int duplicates() { return duplicates; }
}
