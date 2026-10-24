package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.LogText;
import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §L.1 · The CDR's dedup by its key: a session's message is published ONCE per session id — a session restored from an older row and ended
 * again (a death between the final state's synchronous record and the final row) publishes nothing the second time. Bounded (the oldest ids
 * go first); in this process only — across processes the key is billing-core's (the session id and the tier: it takes a call once per tier).
 */
public final class OncePerSessionCdrSink implements CdrSink {

    private static final Logger log = LoggerFactory.getLogger(OncePerSessionCdrSink.class);

    private final CdrSink next;
    private final Map<String, Boolean> published;

    public OncePerSessionCdrSink(CdrSink next, int kept) {
        if (kept <= 0) throw new IllegalArgumentException("kept must be positive");
        this.next = next;
        this.published = new LinkedHashMap<>(256, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > kept; }
        };
    }

    @Override
    public void publish(String callId, List<CdrEvent> tiers) {
        if (firstTime(callId)) next.publish(callId, tiers);
    }

    @Override
    public void publish(String root, String callId, List<CdrEvent> tiers) {
        if (firstTime(callId)) next.publish(root, callId, tiers);
    }

    private synchronized boolean firstTime(String callId) {
        if (published.putIfAbsent(callId, Boolean.TRUE) == null) return true;
        log.warn("{} | its CDR was published already: the second end of the session (a restore from an older row) publishes nothing", LogText.clean(callId));
        return false;
    }

    @Override
    public void close() { next.close(); }
}
