package com.telcobright.seed.sessionflow.internal;

import com.telcobright.seed.sessionflow.api.CdrEvent;
import com.telcobright.seed.sessionflow.spi.CdrSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The CDR road of a kit made from the bootstrap (R13): each served root's own sink — {@code cdr_<root>} on the root's own Kafka. A session
 * names its root; a root with no road is ONE error line with the session (never another root's topic).
 */
public final class PerRootCdrSink implements CdrSink {

    private static final Logger log = LoggerFactory.getLogger(PerRootCdrSink.class);

    private final Map<String, CdrSink> byRoot = new ConcurrentHashMap<>();

    /** The road of one more root (its database name). */
    public void add(String root, CdrSink sink) { byRoot.put(root, sink); }

    /** P1-H · A root's road taken out again (its roads could not all be made); never closed here — its producer may be another root's too. */
    public CdrSink remove(String root) { return byRoot.remove(root); }

    /** The roots with a road. */
    public java.util.Set<String> roots() { return java.util.Set.copyOf(byRoot.keySet()); }

    @Override public void publish(String callId, List<CdrEvent> tiers) { publish(CdrAssembler.rootOf(tiers.get(0)), callId, tiers); }

    @Override
    public void publish(String root, String callId, List<CdrEvent> tiers) {
        CdrSink sink = root == null ? null : byRoot.get(root);
        if (sink == null) { log.error("the CDR of session {} names the root '{}', which has no road here (roots {}) — NOT published", callId, root, byRoot.keySet()); return; }
        sink.publish(root, callId, tiers);
    }

    @Override public void close() { byRoot.values().forEach(CdrSink::close); }
}
